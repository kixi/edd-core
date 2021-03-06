(ns edd.el.cmd-test
  (:require [clojure.test :refer :all]
            [edd.el.cmd :as el-cmd]
            [edd.core :as edd]
            [lambda.core :as core]
            [lambda.filters :as fl]
            [lambda.util :as util]
            [edd.memory.event-store :as event-store]
            [edd.memory.view-store :as view-store]
            [lambda.test.fixture.client :refer [verify-traffic-json]]
            [lambda.test.fixture.core :refer [mock-core]]
            [lambda.api-test :refer [api-request]]
            [lambda.uuid :as uuid]))


(deftest test-empty-commands-list
  (let [ctx {:req {}}]
    (is (= (assoc ctx :error {:commands :empty})
           (el-cmd/validate-commands ctx)))))

(deftest test-invalid-command
  (let [ctx {:commands [{}]}]
    (is (= (assoc ctx :error {:spec [{:cmd-id ["missing required key"]}]})
           (el-cmd/validate-commands ctx)))))

(deftest test-invalid-cmd-id-type
  (let [ctx {:commands [{:cmd-id "wrong"}]}]
    (is (= (assoc ctx :error {:spec [{:cmd-id ["should be a keyword"]}]})
           (el-cmd/validate-commands ctx))))
  (let [ctx {:commands [{:cmd-id :test}]}]
    (is (= ctx
           (el-cmd/validate-commands ctx)))))


(deftest test-custom-schema
  (let [ctx {:spec {:test [:map [:name string?]]}}
        cmd-missing (assoc ctx :commands [{:cmd-id :test}])
        cmd-invalid (assoc ctx :commands [{:cmd-id :test
                                           :name   :wrong}])
        cmd-valid (assoc ctx :commands [{:cmd-id :test
                                         :name   "name"}])]

    (is (= (assoc cmd-missing :error {:spec [{:name ["missing required key"]}]})
           (el-cmd/validate-commands cmd-missing)))

    (is (= (assoc cmd-invalid :error {:spec [{:name ["should be a string"]}]})
           (el-cmd/validate-commands cmd-invalid)))
    (is (= cmd-valid
           (el-cmd/validate-commands cmd-valid)))))

(defn register
  []
  (-> {}
      (event-store/register)
      (view-store/register)
      (edd/reg-cmd :ping
                   (fn [ctx cmd]
                     {:event-id :ping}))))

(deftest api-handler-test
  (let [request-id (uuid/gen)
        interaction-id (uuid/gen)
        cmd {:request-id     request-id,
             :interaction-id interaction-id,
             :commands       [{:cmd-id :ping}]}]
    (mock-core
      :invocations [(api-request cmd)]
      (core/start
        (register)
        edd/handler
        :filters [fl/from-api]
        :post-filter fl/to-api)
      (do
        (verify-traffic-json
          [{:body   {:body            (util/to-json
                                        {:error          {:spec [{:id ["missing required key"]}]}
                                         :request-id     request-id
                                         :interaction-id interaction-id})
                     :headers         {:Access-Control-Allow-Headers  "Id, VersionId, X-Authorization,Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token"
                                       :Access-Control-Allow-Methods  "OPTIONS,POST,PUT,GET"
                                       :Access-Control-Allow-Origin   "*"
                                       :Access-Control-Expose-Headers "*"
                                       :Content-Type                  "application/json"}
                     :isBase64Encoded false
                     :statusCode      200}
            :method :post
            :url    "http://mock/2018-06-01/runtime/invocation/0/response"}
           {:method  :get
            :timeout 90000000
            :url     "http://mock/2018-06-01/runtime/invocation/next"}])))))


(deftest api-handler-response-test
  (let [request-id (uuid/gen)
        interaction-id (uuid/gen)
        id (uuid/gen)
        cmd {:request-id     request-id,
             :interaction-id interaction-id,
             :commands       [{:cmd-id :ping
                               :id     id}]}]
    (mock-core
      :invocations [(api-request cmd)]
      (core/start
        (register)
        edd/handler
        :filters [fl/from-api]
        :post-filter fl/to-api)
      (do
        (verify-traffic-json
          [{:body   {:body            (util/to-json
                                        {:result         {:success    true
                                                          :effects    []
                                                          :events     1
                                                          :meta       [{:ping {:id id}}]
                                                          :identities 0
                                                          :sequences  0}
                                         :request-id     request-id
                                         :interaction-id interaction-id})
                     :headers         {:Access-Control-Allow-Headers  "Id, VersionId, X-Authorization,Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token"
                                       :Access-Control-Allow-Methods  "OPTIONS,POST,PUT,GET"
                                       :Access-Control-Allow-Origin   "*"
                                       :Access-Control-Expose-Headers "*"
                                       :Content-Type                  "application/json"}
                     :isBase64Encoded false
                     :statusCode      200}
            :method :post
            :url    "http://mock/2018-06-01/runtime/invocation/0/response"}
           {:method  :get
            :timeout 90000000
            :url     "http://mock/2018-06-01/runtime/invocation/next"}])))))