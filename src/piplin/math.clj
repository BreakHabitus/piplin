(ns piplin.math
  (:use [slingshot.slingshot])
  (:use (piplin types)))

(defrecord ASTNode [type]
  ITyped
  (typeof [this] type)
  (pipinst? [this] false))

(defmacro mkast
  "Takes the type, op, args, and function and
  returns an ast fragment."
  [type op args f]
  (let [kwargs (vec (map (comp keyword name) args))
        argmap (zipmap kwargs args)]
    `(vary-meta
       (merge (ASTNode. ~type )
              {:op ~op 
               :args ~argmap}) 
       assoc :sim-factory [~f ~kwargs])))

(extend-protocol ITyped
  java.lang.Boolean
  (typeof [this] (anontype :boolean))
  (pipinst? [this] true)
  java.lang.Long
  (typeof [this] (anontype :j-long))
  (pipinst? [this] true)
  java.lang.Integer
  (typeof [this] (anontype :j-int))
  (pipinst? [this] true)
  java.lang.Short
  (typeof [this] (anontype :j-short))
  (pipinst? [this] true)
  java.lang.Byte
  (typeof [this] (anontype :j-byte))
  (pipinst? [this] true)
  java.lang.Float
  (typeof [this] (anontype :j-num))
  (pipinst? [this] true)
  java.lang.Double
  (typeof [this] (anontype :j-num))
  (pipinst? [this] true))

(defn cast
  "Converts the expr to the type."
  [type expr]
  (if (typeof expr)
    (if (pipinst? expr)
      (promote type expr)
      (mkast type :cast [expr] (partial cast type)))
    (try+
      (promote type expr)
      (catch piplin.types.CompilerError e
        (if (class? type)
          (clojure.core/cast type expr)
          (throw+))))))

(defmethod promote 
  :j-integral
  [type obj]
  (condp isa-type? (kindof obj)
    :j-integral (clojure.core/long obj)
    :uintm (:val obj)
    (throw+ (error "Cannot promote" obj "to Long"))))

(derive-type :j-integral :j-num)

(derive-type Double :j-num)
(derive-type Float :j-num)

(derive-type Byte :j-byte)
(derive-type Short :j-short)
(derive-type Integer :j-int)
(derive-type Long :j-long)

(derive-type :j-byte :j-integral)
(derive-type :j-int :j-integral)
(derive-type :j-short :j-integral)
(derive-type :j-long :j-integral)

(defpiplintype UIntM [n])
(defn uintm
  "Make a new uintm type object."
  [n]
  (merge (UIntM. n)
         {:kind :uintm}))

(defmethod constrain
  :uintm
  [this init-val]
  "Takes a uintm (this) and the value
  to initialize the new instance with,
  and constrains the number to be in the
  range of uintm"
  (bit-and init-val (dec (bit-shift-left 1 (:n this)))))

(defmethod check
  :uintm
  [inst]
  (let [n (-> inst typeof :n)
        v (:val inst)
        maxval (dec (bit-shift-left 1 n))]
    (when (< v 0)
      (throw+ (error "uintm must be positive:" v)))
    (when (> v maxval)
      (throw+ (error "uintm" n "must be less than" maxval
                     ", got:" v))))
  inst)

(defmethod promote
  :uintm
  [this obj]
  (cond
    (= (typeof obj) this) obj ;Already correct
    (= (kindof obj)
       (:kind this)) (throw+
                       (error
                         "Incompatible type instances: " this
                         "and" (type obj)))
    (isa-type? :j-integral (kindof obj)) (instance
                                           this
                                           (promote (anontype :j-long)
                                                    obj))
    :else (throw+ (error "Don't know how to promote to :uintm from"
                         (type obj)))))

(defn binary-dispatch
  "Simplified binary dispatch logic."
  [x y]
  [(kindof x) (kindof y)])

(defn nary-dispatch
  "Dispatching logic used by binary math operations"
  ([] ::nullary)
  ([x] (kindof x))
  ([x y] (binary-dispatch x y))
  ([x y & more] ::n-ary))

(defn- make-core-binop-fn
  "Makes syntax for a binop using the core
  implementation for a given hierarchy key"
  [op key]
  `(defmethod ~op [~key ~key]
     [~'x ~'y]
     ('~(ns-resolve 'clojure.core op) ~'x ~'y)))

(defmacro def-n-ary-binop
  "Defines a generic function for a binary operation
  on numeric types. Uses the function in clojure/core
  to provide implementations for Numbers. Handles
  nullary and n-ary invocations by returning zero and
  the left-associative folds, respectively. Existing-types
  is a vector of hierarchy elements whose implementations
  in clojure.core should be integrated."
  [op zero existing-types]
  (let [core-methods (map #(make-core-binop-fn
                             op %)
                          existing-types)]
    `(do
       (defmulti ~op nary-dispatch :hierarchy types)
       (defmethod ~op ::nullary [] ~zero)
       (defmethod ~op ::n-ary
         [~'x ~'y & ~'more]
         (if (seq ~'more)
           (recur (~op ~'x ~'y) (first ~'more) (next ~'more))
           (~op ~'x ~'y)))
       ~@core-methods)))

(defmacro def-binary-binop
  "Like def-n-ary-binop, but without nullary or left-associative
  folds."
  [op existing-types]
  (let [core-methods (map #(make-core-binop-fn
                             op %)
                          existing-types)]
    `(do
       (defmulti ~op binary-dispatch :hierarchy types)
       ~@core-methods)))

(def-n-ary-binop + 0 [:j-num])
(def-n-ary-binop - 0 [:j-num])
(def-n-ary-binop * 0 [:j-num])
(def-n-ary-binop bit-and 0 [:j-num])
(def-n-ary-binop bit-or 0 [:j-num])
(def-n-ary-binop bit-xor 0 [:j-num])

(defn inc
  "Increments x"
  [x]
  (+ x 1))

(defn dec
  "Decrements x"
  [x]
  (- x 1))

(def-binary-binop > [:j-num])
(def-binary-binop < [:j-num])
(def-binary-binop >= [:j-num])
(def-binary-binop <= [:j-num])

(defn not
  [x]
  (if (typeof x)
    (if (pipinst? x)
      (clojure.core/not x) 
      (mkast (anontype :boolean) :not [x] not))
    (clojure.core/not x)))

(defmulti = nary-dispatch :hierarchy types)
(defmethod = :default [x y]
  (clojure.core/= x y))
(defmethod = ::n-ary
  [x y & more]
  (if (= x y)
    (if (seq more)
      (recur x (first more) (rest more))
      true)
    false))

(defn not=
  ([x] false)
  ([x y] (not (= x y)))
  ([x y & more] (not (apply = x y more))))

(defn- make-binop-impl-fn
  "Takes a kind, fntail, and an symbol for the op
  and returns a function which should be invoked
  with 2 arguments of the appropriate kind, and
  it will evaluate them if the arguments are
  immediate and it will return an AST expr if one
  or more of the arguments aren't immediates."
  [op unmangled-kw k fntail]
  `(defmethod ~op [~k ~k]
     [x# y#]
     (if (and (pipinst? x#)
              (pipinst? y#))
       (instance (typeof x#)
                 ((fn ~@fntail) x# y#)
                 :constrain)
       (let [~'lhs x# ~'rhs y#]
         (mkast (typeof x#)
                ~unmangled-kw
                [~'lhs ~'rhs] ~op)))))

(defn make-binop-explict-coercions
  "Takes a kind and a vector of kinds and returns
  a list of syntax for defmethods that will invoke
  the method for [kind kind] if kind is one of the
  arguments and any element of the vector is the
  other argument's kind."
  [op k bases]
  (let [k-bases (map #(vector k %) bases)
        dispatches (concat k-bases
                           (map reverse k-bases))]
    (map (fn [[a b]]
           `(defmethod ~op [~a ~b]
              [~'x ~'y]
              (let [[~'x  ~'y] (type-unify ~k ~'x ~'y)]
                (~op ~'x ~'y))))
         dispatches)))

(defmacro defcoercions
  "Used to construct automatic coercion for binop types
  when they don't follow the more common return value
  pattern."
  [op k bases]
  `(do ~@(make-binop-explict-coercions op k bases)))

(defmacro defbinopimpl
  "Defines implementation of a binop on a piplin kind.
  Requires the kind to attempt to unify to, the list
  of types which can be promoted to the kind, and an
  fntail that takes 2 arguments and returns the result.
  The implementation will return an error if the
  unification failed."
  [op k bases & fntail]
  (let [unmangled-kw (keyword (name op))
        impl-body (make-binop-impl-fn op unmangled-kw
                                      k fntail)
        bodies (make-binop-explict-coercions op k bases)]
    `(do
       ~impl-body
       ~@bodies)))

(defbinopimpl + :uintm [:j-integral]
  [x y]
  (+ (:val x) (:val y)))

(defbinopimpl - :uintm [:j-integral]
  [x y]
  (- (:val x) (:val y)))

(defbinopimpl * :uintm [:j-integral]
  [x y]
  (* (:val x) (:val y)))

(defmethod > [:uintm :uintm]
  [x y]
  (if (and (pipinst? x) (pipinst? y))
    (> (:val x) (:val y))
    (mkast (anontype :boolean) :> [x y] >)))
(defcoercions > :uintm [:j-integral])

(defmethod >= [:uintm :uintm]
  [x y]
  (if (and (pipinst? x) (pipinst? y))
    (>= (:val x) (:val y))
    (mkast (anontype :boolean) :>= [x y] >=)))
(defcoercions >= :uintm [:j-integral])

(defmethod < [:uintm :uintm]
  [x y]
  (if (and (pipinst? x) (pipinst? y))
    (< (:val x) (:val y))
    (mkast (anontype :boolean) :< [x y] <)))
(defcoercions < :uintm [:j-integral])

(defmethod <= [:uintm :uintm]
  [x y]
  (if (and (pipinst? x) (pipinst? y))
    (<= (:val x) (:val y))
    (mkast (anontype :boolean) :<= [x y] <=)))
(defcoercions <= :uintm [:j-integral])

(defmethod = [:uintm :uintm]
  [x y]
  (if (and (pipinst? x) (pipinst? y))
    (= (:val x) (:val y))
    (mkast (anontype :boolean) := [x y] =)))
(defcoercions = :uintm [:j-integral])

(defbinopimpl bit-and :uintm [:j-integral]
  [x y]
  (bit-and (:val x) (:val y)))

(defbinopimpl bit-or :uintm [:j-integral]
  [x y]
  (bit-or (:val x) (:val y)))

(defbinopimpl bit-xor :uintm [:j-integral]
  [x y]
  (bit-xor (:val x) (:val y)))

(defn long-to-bitvec
  "Takes a long and returns a bitvec with the first n bits"
  [l n]
  (->> (iterate #(bit-shift-right % 1) l)
    (map #(bit-and 1 %))
    (take n)
    reverse
    vec))

(defn bitvec-to-long
  "Takes a bitvec and returns a long"
  [bv]
  (if (> (count bv) 64)
    (throw (RuntimeException. "bitvec too long"))
    (reduce #(bit-or (bit-shift-left %1 1) (long %2))
            0 bv)))

(defmethod promote
  :bits
  [type obj]
  (when-let [n (-> obj typeof :n)]
    (when-not (= (:n type) n)
      (throw+ (error "Bit size mismatch"))))
  (condp isa-type? (kindof obj)
    :bits obj
    :uintm (instance type
                     (long-to-bitvec (:val obj)
                                     (:n type)))
    :j-integral (instance type
                          (long-to-bitvec obj
                                          (:n type)))
    (throw+ (error "Cannot promote" obj "to bits"))))

(defpiplintype Bits [n])
(defn bits
  "Make a new bits type object."
  [n]
  (merge (Bits. n)
         {:kind :bits}))

(defmethod check
  :bits
  [inst]
  (let [n (-> inst typeof :n)
        v (:val inst)]
    (when-not (and (vector? v)
                   (every? #{0 1} v))
      (throw+ (error
                "bits must be a vector of 0s and 1s:" v)))
    (when (not= (count v) n)
      (throw+ (error "bit vector must be of length" n (count  v)))))
  inst)

(defmulti get-bits
  (fn [expr] (kindof expr))
  :hierarchy types)

(defmethod get-bits
  :default
  [expr]
  (throw+ (error "Cannot convert " expr " to bits")))

(defmethod get-bits
  :uintm
  [expr]
  (let [n (-> expr typeof :n)
        type (bits n)]
    (if (pipinst? expr)
      (instance type (long-to-bitvec (:val expr) n))
      (mkast type :get-bits [expr] get-bits))))

(defn slice-impl
  "Does slicing of bits"
  [expr low high]
  (let [type (bits (- high low))]
    (instance type (-> (:val expr)
                     reverse
                     vec
                     (subvec low high)
                     reverse
                     vec))))

;This might not need to be defn-errors
(defn bit-slice
  "Takes an expr of type bits and returns a subrange
  of the bits."
  [expr low high]
  (if-not (= (kindof expr) :bits)
    (throw+ (error "Can only slice bits, not " expr))
    (let [type (bits (- high low))]
      (if (pipinst? expr)
        (slice-impl expr low high)
        (merge
          (mkast type :slice
                 [expr] #(slice-impl % low high))
          {:low low
           :high high})))))

(defn bit-cat
  ([]
   (instance (bits 0) []))
  ([bs]
   bs)
  ([b1 b2]
   (instance (bits (+ (-> b1 typeof :n) (-> b2 typeof :n)))
             (vec (concat (:val b1) (:val b2)))))
  ([b1 b2 & more]
   (if more
     (recur (bit-cat b1 b2) (first more) (next more))
     (bit-cat b1 b2))))

(defbinopimpl bit-and :bits [:uintm :j-integral]
  [x y]
  (vec (map #(bit-and %1 %2)
            (:val x) (:val y))))

(defbinopimpl bit-or :bits [:uintm :j-integral]
  [x y]
  (vec (map #(bit-or %1 %2)
            (:val x) (:val y))))

(defbinopimpl bit-xor :bits [:uintm :j-integral]
  [x y]
  (vec (map #(bit-xor %1 %2)
            (:val x) (:val y))))

(defn mux2
  [sel v1 v2]
  (when-not (= (typeof v1) (typeof v2))
    (throw+ (error v1 "and" v2 "are different types" (typeof v1) (typeof v2))))
  (let [sel (cast (anontype :boolean) sel)]
    (if (pipinst? sel)
      (if sel v1 v2)
      (mkast (typeof v1) :mux2 [sel v1 v2] mux2))))


(defmethod promote
  :boolean
  [type obj]
  (= 1
     (cond
       (= (typeof obj) type) (if obj 1 0)
       (or (= true obj) (= false obj)) (if obj 1 0)
       (= (typeof obj) (bits 1)) (first (:val obj))
       (and (= (kindof obj) :uintm)
            (= (-> obj typeof :n) 1)) (:val obj)
       :else
       (throw+ (error "Cannot promote" obj "to boolean")))))

(defn trace
  "Takes a function and an expr and returns an
  expr whose value is the expr, but when simulated
  will run the given function for its side effects
  every time this value is used."
  [f expr]
  (mkast (typeof expr) :noop [expr] #(do (f %) %)))

(defn pr-trace
  "Prints the args followed by the traced value."
  [& args]
  (trace #(apply print-str (conj (butlast args) %))
         (last args)))

