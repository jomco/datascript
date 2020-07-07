(ns datascript.test.tuples
  (:require
    #?(:cljs [cljs.test    :as t :refer-macros [is are deftest testing]]
       :clj  [clojure.test :as t :refer        [is are deftest testing]])
    [datascript.core :as d]
    [datascript.test.core :as tdc])
  (:import #?(:clj [clojure.lang ExceptionInfo])))

(deftest test-schema
  (let [db (d/empty-db
             {:year+session {:db/tupleAttrs [:year :session]}
              :semester+course+student {:db/tupleAttrs [:semester :course :student]}
              :session+student {:db/tupleAttrs [:session :student]}})]
    (is (= #{:year+session :semester+course+student :session+student}
          (:db.type/tuple (:rschema db))))

    (is (= {:year     {:year+session 0}
            :session  {:year+session 1, :session+student 0}
            :semester {:semester+course+student 0}
            :course   {:semester+course+student 1}
            :student  {:semester+course+student 2, :session+student 1}}
          (:db/attrTuples (:rschema db))))

    (is (thrown-msg? ":t2 :db/tupleAttrs can’t depend on another tuple attribute: :t1"
          (d/empty-db {:t1 {:db/tupleAttrs [:a :b]}
                       :t2 {:db/tupleAttrs [:c :d :e :t1]}})))

    (is (thrown-msg? ":t1 :db/tupleAttrs must be a sequential collection, got: :a"
          (d/empty-db {:t1 {:db/tupleAttrs :a}})))

    (is (thrown-msg? ":t1 :db/tupleAttrs can’t be empty"
          (d/empty-db {:t1 {:db/tupleAttrs ()}})))

    (is (thrown-msg? ":t1 has :db/tupleAttrs, must be :db.cardinality/one"
          (d/empty-db {:t1 {:db/tupleAttrs [:a :b :c]
                            :db/cardinality :db.cardinality/many}})))

    (is (thrown-msg? ":t1 :db/tupleAttrs can’t depend on :db.cardinality/many attribute: :a"
          (d/empty-db {:a  {:db/cardinality :db.cardinality/many}
                       :t1 {:db/tupleAttrs [:a :b :c]}})))))

(deftest test-tx
  (let [conn (d/create-conn {:a+b   {:db/tupleAttrs [:a :b]}
                             :a+c+d {:db/tupleAttrs [:a :c :d]}})]
    (are [tx datoms] (= datoms (tdc/all-datoms (:db-after (d/transact! conn tx))))
      [[:db/add 1 :a "a"]]
      #{[1 :a     "a"]
        [1 :a+b   ["a" nil]]
        [1 :a+c+d ["a" nil nil]]}

      [[:db/add 1 :b "b"]]
      #{[1 :a     "a"]
        [1 :b     "b"]
        [1 :a+b   ["a" "b"]]
        [1 :a+c+d ["a" nil nil]]}

      [[:db/add 1 :a "A"]]
      #{[1 :a     "A"]
        [1 :b     "b"]
        [1 :a+b   ["A" "b"]]
        [1 :a+c+d ["A" nil nil]]}

      [[:db/add 1 :c "c"]
       [:db/add 1 :d "d"]]
      #{[1 :a     "A"]
        [1 :b     "b"]
        [1 :a+b   ["A" "b"]]
        [1 :c     "c"]
        [1 :d     "d"]
        [1 :a+c+d ["A" "c" "d"]]}

      [[:db/add 1 :a "a"]]
      #{[1 :a     "a"]
        [1 :b     "b"]
        [1 :a+b   ["a" "b"]]
        [1 :c     "c"]
        [1 :d     "d"]
        [1 :a+c+d ["a" "c" "d"]]}

      [[:db/add 1 :a "A"]
       [:db/add 1 :b "B"]
       [:db/add 1 :c "C"]
       [:db/add 1 :d "D"]]
      #{[1 :a     "A"]
        [1 :b     "B"]
        [1 :a+b   ["A" "B"]]
        [1 :c     "C"]
        [1 :d     "D"]
        [1 :a+c+d ["A" "C" "D"]]}

      [[:db/retract 1 :a "A"]]
      #{[1 :b     "B"]
        [1 :a+b   [nil "B"]]
        [1 :c     "C"]
        [1 :d     "D"]
        [1 :a+c+d [nil "C" "D"]]}

      [[:db/retract 1 :b "B"]]
      #{[1 :c     "C"]
        [1 :d     "D"]
        [1 :a+c+d [nil "C" "D"]]})

    (is (thrown-msg? "Can’t modify tuple attrs directly: [:db/add 1 :a+b [\"A\" \"B\"]]"
          (d/transact! conn [{:db/id 1 :a+b ["A" "B"]}])))))

(deftest test-unique
  (let [conn (d/create-conn {:a+b {:db/tupleAttrs [:a :b]
                                   :db/unique :db.unique/identity}})]
    (d/transact! conn [[:db/add 1 :a "a"]])
    (d/transact! conn [[:db/add 2 :a "A"]])
    (is (thrown-with-msg? ExceptionInfo #"Cannot add .* because of unique constraint: .*"
          (d/transact! conn [[:db/add 1 :a "A"]])))

    (d/transact! conn [[:db/add 1 :b "b"]
                       [:db/add 2 :b "b"]
                       {:db/id 3 :a "a" :b "B"}])

    (is (= #{[1 :a "a"]
             [1 :b "b"]
             [1 :a+b ["a" "b"]]
             [2 :a "A"]
             [2 :b "b"]
             [2 :a+b ["A" "b"]]
             [3 :a "a"]
             [3 :b "B"]
             [3 :a+b ["a" "B"]]}
          (tdc/all-datoms (d/db conn))))

    (is (thrown-with-msg? ExceptionInfo #"Cannot add .* because of unique constraint: .*"
          (d/transact! conn [[:db/add 1 :a "A"]])))
    (is (thrown-with-msg? ExceptionInfo #"Cannot add .* because of unique constraint: .*"
          (d/transact! conn [[:db/add 1 :b "B"]])))
    (is (thrown-with-msg? ExceptionInfo #"Cannot add .* because of unique constraint: .*"
          (d/transact! conn [[:db/add 1 :a "A"]
                             [:db/add 1 :b "B"]])))

    (testing "multiple tuple updates"
      ;; changing both tuple components in a single operation
      (d/transact! conn [{:db/id 1 :a "A" :b "B"}])
      (is (= {:db/id 1 :a "A" :b "B" :a+b ["A" "B"]}
            (d/pull (d/db conn) '[*] 1)))

      ;; adding entity with two tuple components in a single operation
      (d/transact! conn [{:db/id 4 :a "a" :b "b"}])
      (is (= {:db/id 4 :a "a" :b "b" :a+b ["a" "b"]}
            (d/pull (d/db conn) '[*] 4))))))

(deftest test-upsert
  (let [conn (d/create-conn {:a+b {:db/tupleAttrs [:a :b]
                                   :db/unique :db.unique/identity}
                             :c   {:db/unique :db.unique/identity}})]
    (d/transact! conn
      [{:db/id 1 :a "A" :b "B"}
       {:db/id 2 :a "a" :b "b"}])

    (d/transact! conn [{:a+b ["A" "B"] :c "C"}
                       {:a+b ["a" "b"] :c "c"}])
    (is (= #{[1 :a "A"]
             [1 :b "B"]
             [1 :a+b ["A" "B"]]
             [1 :c "C"]
             [2 :a "a"]
             [2 :b "b"]
             [2 :a+b ["a" "b"]]
             [2 :c "c"]}
          (tdc/all-datoms (d/db conn))))  

    (is (thrown-msg? "Conflicting upserts: [:a+b [\"A\" \"B\"]] resolves to 1, but [:c \"c\"] resolves to 2"
          (d/transact! conn [{:a+b ["A" "B"] :c "c"}])))

    ;; change tuple + upsert
    (d/transact! conn
      [{:a+b ["A" "B"]
        :b "b"
        :d "D"}])

    (is (= #{[1 :a "A"]
             [1 :b "b"]
             [1 :a+b ["A" "b"]]
             [1 :c "C"]
             [1 :d "D"]
             [2 :a "a"]
             [2 :b "b"]
             [2 :a+b ["a" "b"]]
             [2 :c "c"]}
          (tdc/all-datoms (d/db conn))))))

(deftest test-lookup-refs
  (let [conn (d/create-conn {:a+b {:db/tupleAttrs [:a :b]
                                   :db/unique :db.unique/identity}
                             :c   {:db/unique :db.unique/identity}})]
    (d/transact! conn
      [{:db/id 1 :a "A" :b "B"}
       {:db/id 2 :a "a" :b "b"}])

    (d/transact! conn [[:db/add [:a+b ["A" "B"]] :c "C"]
                       {:db/id [:a+b ["a" "b"]] :c "c"}])
    (is (= #{[1 :a "A"]
             [1 :b "B"]
             [1 :a+b ["A" "B"]]
             [1 :c "C"]
             [2 :a "a"]
             [2 :b "b"]
             [2 :a+b ["a" "b"]]
             [2 :c "c"]}
          (tdc/all-datoms (d/db conn))))  

    (is (thrown-with-msg? ExceptionInfo #"Cannot add .* because of unique constraint: .*"
          (d/transact! conn [[:db/add [:a+b ["A" "B"]] :c "c"]])))

    (is (thrown-msg? "Conflicting upsert: [:c \"c\"] resolves to 2, but entity already has :db/id 1"
          (d/transact! conn [{:db/id [:a+b ["A" "B"]] :c "c"}])))

    ;; change tuple + upsert
    (d/transact! conn
      [{:db/id [:a+b ["A" "B"]]
        :b "b"
        :d "D"}])

    (is (= #{[1 :a "A"]
             [1 :b "b"]
             [1 :a+b ["A" "b"]]
             [1 :c "C"]
             [1 :d "D"]
             [2 :a "a"]
             [2 :b "b"]
             [2 :a+b ["a" "b"]]
             [2 :c "c"]}
          (tdc/all-datoms (d/db conn))))))

(deftest test-validation
  (let [db  (d/empty-db {:a+b {:db/tupleAttrs [:a :b]}})
        db1 (d/db-with db [[:db/add 1 :a "a"]])]
    (is (thrown-msg? "Can’t modify tuple attrs directly: [:db/add 1 :a+b [nil nil]]"
          (d/db-with db [[:db/add 1 :a+b [nil nil]]])))
    (is (thrown-msg? "Can’t modify tuple attrs directly: [:db/add 1 :a+b [\"a\" nil]]"
          (d/db-with db1 [[:db/add 1 :a+b ["a" nil]]])))
    (is (thrown-msg? "Can’t modify tuple attrs directly: [:db/add 1 :a+b [\"a\" nil]]"
          (d/db-with db [[:db/add 1 :a "a"]
                         [:db/add 1 :a+b ["a" nil]]])))
    (is (thrown-msg? "Can’t modify tuple attrs directly: [:db/retract 1 :a+b [\"a\" nil]]"
          (d/db-with db1 [[:db/retract 1 :a+b ["a" nil]]])))))