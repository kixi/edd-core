(ns edd.sequence-test
  (:require
    [clojure.test :refer :all]
    [lambda.core :refer [handle-request]]
    [edd.test.fixture.dal :as mock]
    [edd.core :as edd]
    [edd.memory.view-store :as view-store]
    [edd.memory.event-store :as event-store]
    [lambda.uuid :as uuid]))

(def ctx
  (-> {}
      (view-store/register)
      (event-store/register)
      (edd/reg-cmd :create-1 (fn [ctx cmd]
                               [{:sequence :limit-application
                                 :id       (:id cmd)}
                                {:event-id :e1
                                 :name     (:name cmd)}]))))

(deftest test-sequence-generation
  (mock/with-mock-dal
    (let [id (uuid/gen)
          resp (mock/execute-cmd
                 ctx
                 {:cmd-id :create-1
                  :id     id
                  :name   "e1"})]

      (mock/verify-state :sequence-store [{:value 1
                                           :id    id}])
      (is (= {:effects    []
              :events     1
              :identities 0
              :meta      [{:create-1 {:id id}}]
              :sequences  1
              :success    true}
             resp)))))
