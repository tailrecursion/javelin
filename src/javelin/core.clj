;; Copyright (c) Alan Dipert and Micha Niskin. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns javelin.core
  (:refer-clojure :exclude [dosync])
  (:require
    [cljs.core]
    [clojure.walk    :refer [prewalk]]
    [clojure.pprint  :as p]
    [cljs.analyzer   :as a]
    [clojure.set :as set]))

(declare walk)

;; util ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro ^:private with-let*
  "Binds resource to binding and evaluates body.  Then, returns
  resource.  It's a cross between doto and with-open."
  [[binding resource] & body]
  `(let [~binding ~resource] ~@body ~binding))

(def destructure*
  "Select a version of #'destructure that works with the version of the CLJS
  compiler that is provided. Older versions of CLJS do not provide destructure
  so we fall back to using Clojure's destructure function in that case."
  (if-not (resolve 'cljs.core/destructure)
    destructure
    cljs.core/destructure))

(defn extract-syms
  "Extract symbols that will be bound by bindings, including autogenerated
  symbols produced for destructuring."
  [bindings]
  (map first (partition 2 (destructure* bindings))))

(defn extract-syms-without-autogen
  "Extract only the symbols that the user is binding from bindings, omitting
  any intermediate autogenerated bindings used for destructuring. A trick is
  used here taking advantage of the fact that gensym names are produced as a
  side effect -- successive calls to extract-syms are not redundant."
  [bindings]
  (let [syms1 (set (extract-syms bindings))
        syms2 (set (extract-syms bindings))]
    (seq (set/intersection syms1 syms2))))

(defn bind-syms
  "Given a binding form, returns a seq of the symbols that will be bound.

  (bind-syms '[{:keys [foo some.ns/bar] :as baz} baf & quux])
  ;=> (foo bar baz baf quux)"
  [form]
  (extract-syms-without-autogen [form nil]))

(defn macroexpand*
  "Expand form if it is a CLJS macro, otherwise just return form."
  [env form]
  (if (seq? form)
    (let [ex (a/macroexpand-1 env form)]
      (if (identical? ex form)
        form
        (macroexpand* env ex)))
    form))

(defn macroexpand-all*
  "Fully expand all CLJS macros contained in form."
  [env form]
  (prewalk (partial macroexpand* env) form))

(defmacro macroexpand-all
  "Fully expand all CLJS macros contained in form."
  [form]
  (macroexpand-all* &env form))

(defmacro mx
  "Expand all macros in form and pretty-print them (as code)."
  [form]
  `(println
     ~(with-out-str
        (p/write (macroexpand-all* &env form) :dispatch p/code-dispatch))))

(defmacro mx2
  "Expand all macros in form and pretty-print them (as data)."
  [form]
  `(println
     ~(with-out-str
        (p/write (macroexpand-all* &env form)))))

;; javelin cells ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *env*    nil)
(def ^:dynamic *hoist*  nil)
(def ^:dynamic *pass*   nil)

(create-ns 'js)

(let [to-list   #(into '() (reverse %))
      special   a/specials
      special?  #(contains? special %)
      unsupp?*  #(contains? '#{def ns deftype* defrecord*} %)
      core?     #(contains? #{"clojure.core" "cljs.core" "js"} (namespace %))
      empty?*   #(= 0 (count %))
      dot?      #(= '. (first %))
      try?      #(= 'try (first %))
      finally?  #(= 'finally (first %))
      binding1? #(contains? '#{let* loop*} (first %))
      binding2? #(= 'letfn* (first %))
      binding3? #(= 'fn* (first %))
      catch?    #(= 'catch (first %))
      quoted?   #(= 'quote (first %))
      unwrap1?  #(= 'clojure.core/unquote (first %))
      unwrap2?  #(= 'clojure.core/unquote-splicing (first %))
      err1      #(format "formula expansion contains unsupported %s form" %)]

  (defn unsupp? [x local]
    (let [op (first x)]
      (and (not (*env* op)) (not (local op)) (unsupp?* op))))

  (defn hoist? [x local]
    (and (not (or (local x) (core? x))) (or (*env* x) (not (special? x)))))

  (defn walk-sym [x local]
    (if-not (hoist? x local)
      x
      (let [h (@*hoist* x)]
        (when-not h (swap! *hoist* conj (with-meta x {::h (gensym)})))
        (::h (meta (@*hoist* x))))))

  (defn walk-map [x local]
    (into (empty x) (map #(mapv (fn [x] (walk x local)) %) x)))

  (defn walk-seq [x local]
    (into (empty x) (map #(walk % local) x)))

  (defn walk-bind1 [[sym bindings & body] local]
    (let [local     (atom local)
          bind1     (fn [[k v]]
                      (with-let* [x [k (walk v @local)]]
                        (swap! local conj k)))
          bindings  (mapcat bind1 (partition 2 bindings))]
      (to-list `(~sym [~@bindings] ~@(map #(walk % @local) body)))))

  (defn walk-catch [[sym etype bind & body] local]
    (to-list `(~sym ~etype ~bind ~@(map #(walk % (conj local bind)) body))))

  (defn walk-finally [[sym & body] local]
    (to-list `(~sym ~@(map #(walk % local) body))))

  (defn walk-try [[sym & body] local]
    (to-list `(~sym ~@(map #((cond (not (seq? %)) walk
                                   (catch?   %)   walk-catch
                                   (finally? %)   walk-finally
                                   :else          walk)
                             % local)
                           body))))

  (defn walk-bind2 [[sym bindings & body] local]
    (let [local     (reduce conj local (map first (partition 2 bindings)))
          bindings  (map #(%1 %2) (cycle [identity #(walk % local)]) bindings)]
      (to-list `(~sym [~@bindings] ~@(map #(walk % local) body)))))

  (defn walk-bind3 [[sym & arities] local]
    (let [fname   (when (symbol? (first arities)) [(first arities)])
          arities (if fname (rest arities) arities)
          arities (if (vector? (first arities)) [arities] arities)
          local   (if fname (conj local (first fname)) local)
          mkarity (fn [[bindings & body]]
                    (let [local (into local (remove #(= '& %) bindings))]
                      (to-list `([~@bindings] ~@(map #(walk % local) body)))))
          arities (map mkarity arities)]
      (to-list `(~sym ~@fname ~@arities))))

  (defn walk-passthru [x _local]
    (with-let* [s (gensym)] (swap! *pass* assoc s x)))

  (defn walk-dot [[sym obj meth & more] local]
    (let [obj       (walk obj local)
          more      (map #(walk % local) more)
          walk-meth (fn [m] (list (first m) (map #(walk % local) (rest m))))]
      (to-list `(~sym ~obj ~@(if-not (seq? meth) `[~meth ~@more] [(walk-meth meth)])))))

  (defn walk-list [x local]
    (let [unsupp? #(unsupp? % local)]
      (cond (empty?*   x) x
            (dot?      x) (walk-dot x local)
            (try?      x) (walk-try x local)
            (binding1? x) (walk-bind1 x local)
            (binding2? x) (walk-bind2 x local)
            (binding3? x) (walk-bind3 x local)
            (quoted?   x) (walk-passthru x local)
            (unwrap1?  x) (walk-passthru (second x) local)
            (unwrap2?  x) (walk-passthru (list 'deref (second x)) local)
            (unsupp?   x) (throw (Exception. (err1 (first x))))
            :else         (to-list (map #(walk % local) x)))))

  (defn walk [x local]
    (cond (symbol? x) (walk-sym x local)
          (map?    x) (walk-map x local)
          (set?    x) (walk-seq x local)
          (vector? x) (walk-seq x local)
          (seq?    x) (walk-list x local)
          :else       x))

  (defn hoist [x env]
    (binding [*env* env, *hoist* (atom #{}), *pass* (atom {})]
      (let [body          (walk (macroexpand-all* env x) #{})
            [params args] (if (empty? @*pass*) [[] []] (apply map vector @*pass*))
            params        (into params (map #(::h (meta %)) @*hoist*))
            args          (into args @*hoist*)]
        [(list 'fn params body) args])))

  (defn cell* [x env]
    (let [[f args] (hoist x env)]
      (to-list `((formula ~f) ~@args)))))

;; javelin CLJS macros ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro with-let
  "Binds resource to binding and evaluates body.  Then, returns
  resource.  It's a cross between doto and with-open."
  [[binding resource] & body]
  `(let [~binding ~resource] ~@body ~binding))

(defmacro cell=
  ([expr] (cell* expr &env))
  ([expr f]
   `(with-let [c# (cell= ~expr)]
      (set! (.-update c#) ~f))))

(defmacro set-cell!=
  ([c expr]
   `(set-cell!= ~c ~expr nil))
  ([c expr updatefn]
   (let [[f args] (hoist expr &env)]
     `(set-formula! ~c ~f [~@args] ~updatefn))))

(defmacro defc
  ([sym expr] `(def ~sym (cell ~expr)))
  ([sym doc expr] `(def ~sym ~doc (cell ~expr))))

(defmacro defc=
  ([sym expr] `(def ~sym (cell= ~expr)))
  ([sym doc & [expr f]]
   (let [doc? (string? doc)
         f    (when-let [f' (if doc? f expr)] [f'])
         expr (if doc? expr doc)
         doc  (when doc? [doc])]
     `(def ~sym ~@doc (cell= ~expr ~@f)))))

(defmacro formula-of
  "ALPHA: this macro may change.

  Given a vector of dependencies and one or more body expressions, emits a
  form that will produce a formula cell. The dependencies must be names that
  will be re-bound to their values within the body. No code walking is done.
  The value of the formula cell is computed by evaluating the body expressions
  whenever any of the dependencies change.

  Note: the dependencies need not be cells.

  E.g.
      (def x 100)
      (def y (cell 200))
      (def z (cell= (inc y)))

      (def c (formula-of [x y z] (+ x y z)))

      (deref c) ;=> 501

      (swap! y inc)
      (deref c) ;=> 503
  "
  [deps & body]
  (assert (and (vector? deps) (every? symbol? deps))
          "first argument must be a vector of symbols")
  `((formula (fn [~@deps] ~@body)) ~@deps))

(defmacro formulet
  "ALPHA: this macro may change.

  Given a vector of binding-form/dependency pairs and one or more body
  expressions, emits a form that will produce a formula cell. Each binding
  form is bound to the value of the corresponding dependency within the body.
  No code walking is done. The value of the formula cell is computed by
  evaluating the body expressions whenever any of the dependencies change.

  Note: the depdendency expressions are evaluated only once, when the formula
  cell is created, and they need not evaluate to javelin cells.

  E.g.
      (def a (cell 42))
      (def b (cell {:x 100 :y 200}))

      (def c (formulet [v (cell= (inc a))
                        w (+ 1 2)
                        {:keys [x y]} b]
                (+ v w x y)))

      (deref c) ;=> 346

      (swap! a inc)
      (deref c) ;=> 347
  "
  [bindings & body]
  (assert (and (vector? bindings) (even? (count bindings)))
          "first argument must be a vector of binding pairs")
  (let [binding-pairs (partition 2 bindings)]
    `((formula (fn [~@(map first binding-pairs)] ~@body))
      ~@(map second binding-pairs))))

(defmacro ^:private cell-let-1 [[bindings c] & body]
  (let [syms  (bind-syms bindings)
        dcell `((formula (fn [~bindings] [~@syms])) ~c)]
    `(let [[~@syms] (cell-map identity ~dcell)] ~@body)))

(defmacro cell-let
  [[bindings c & more] & body]
  (if-not (seq more)
    `(cell-let-1 [~bindings ~c] ~@body)
    `(cell-let-1 [~bindings ~c]
                 (cell-let ~(vec more) ~@body))))

(defmacro dosync
  "Evaluates the body within a Javelin transaction. Propagation of updates
  to formula cells is deferred until the transaction is complete. Input
  cells *will* update during the transaction. Transactions may be nested."
  [& body]
  `(dosync* (fn [] ~@body)))

(defmacro cell-doseq
  "Takes a vector of binding-form/collection-cell pairs and one or more body
  expressions, similar to clojure.core/doseq. Iterating over the collection
  cells produces a sequence of items that may grow, shrink, or update over
  time. Whenever this sequence grows the body expressions are evaluated (for
  side effects) exactly once for each new location in the sequence. Bindings
  are bound to cells that refer to the item at that location.

  Consider:

      (def things (cell [{:x :a} {:x :b} {:x :c}]))

      (cell-doseq [{:keys [x]} things]
        (prn :creating @x)
        (add-watch x nil #(prn :updating %3 %4)))

      ;; the following is printed -- note that x is a cell:

      :creating :a
      :creating :b
      :creating :c

  Shrink things by removing the last item:

      (swap! things pop)

      ;; the following is printed (because the 3rd item in things is now nil,
      ;; since things only contains 2 items) -- note that the doit function is
      ;; not called (or we would see a :creating message):

      :updating :c nil

  Grow things such that it is one item larger than it ever was:

      (swap! things into [{:x :u} {:x :v}])

      ;; the following is printed (because things now has 4 items, so the 3rd
      ;; item is now {:x :u} and the max size increases by one with the new
      ;; item {:x :v}):

      :updating nil :u
      :creating :v

  A weird imagination is most useful to gain full advantage of all the features."
  [bindings & body]
  (if (= 2 (count bindings))
    `(cell-doseq*
       ((formula seq) ~(second bindings))
       (fn [item#] (cell-let [~(first bindings) item#] ~@body)))
    (let [pairs   (partition 2 bindings)
          lets    (->> pairs (filter (comp (partial = :let) first)) (mapcat second))
          binds*  (->> pairs (take-while (complement (comp keyword? first))))
          mods*   (->> pairs (drop-while (complement (comp keyword? first))) (mapcat identity))
          syms    (->> binds* (mapcat (comp bind-syms first)))
          exprs   (->> binds* (map second))
          gens    (take (count exprs) (repeatedly gensym))
          fors    (-> (->> binds* (map first)) (interleave gens) (concat mods*))]
      `(cell-doseq*
         ((formula (fn [~@gens] (for [~@fors] [~@syms]))) ~@exprs)
         (fn [item#] (cell-let [[~@syms] item#, ~@lets] ~@body))))))

;; FIXME: this macro doesn't work correctly, maybe mutation observers?
(defmacro prop-cell
  ([prop]
   `(let [ret# (cell ~prop)]
      (js/setInterval #(reset! ret# ~prop) 100)
      (cell= ret#)))
  ([prop setter & [callback]]
   `(let [setter#   ~setter
          callback# (or ~callback identity)]
      (cell= (set! ~prop setter#))
      (js/setInterval
        #(when (not= @setter# ~prop)
           (callback# ~prop)
           (set! ~prop @setter#))
        100)
      setter#)))
