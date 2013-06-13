(ns pallet.crate.rabbitmq-test
  (:use pallet.crate.rabbitmq)
  (:require
   [pallet.build-actions :as build-actions]
   [pallet.actions :as actions]
   [pallet.test-utils :as test-utils]
   [pallet.crate.erlang-config :as erlang-config]
   [pallet.stevedore :as sd]
   [pallet.crate.rabbitmq :as rabbitmq])
  (:use clojure.test))

(deftest erlang-config-test
  (is (= "[{mnesia, [{dump_log_write_threshold, 1000}]},{rabbit, []}]."
         (erlang-config/as-string {:mnesia {:dump_log_write_threshold 1000}
                                   :rabbit {}})))
  (testing "lazy seq"
    (is (= "[{mnesia, [{dump, [0,1,2]}]}]."
         (erlang-config/as-string {:mnesia {:dump (range 3)}})))))

(deftest configure-test
  (let [node (test-utils/make-node "id" :ip "12.3.4.5")
        {:keys [owner group config-dir env-file]} (sd/with-script-language :pallet.stevedore.bash/bash
                                                                           (rabbitmq/default-settings {}))]
    (testing "Default config"
      (is (build-actions/build-actions
           {}
           (rabbitmq/settings {:config-file "cf"})
           (rabbitmq/configure {:rabbit {}}))))
    (testing "Starting service"
      (is (build-actions/build-actions
           {}
           (rabbitmq/settings {})
           (rabbitmq/install {})
           (rabbitmq/configure {})
           (rabbitmq/service))))))

;;     (testing "Default config"
;;       (is (= (first
;;               (build-actions/build-actions
;;                  {}
;;                  (actions/directory config-dir :owner owner :group group)
;;                  (actions/remote-file env-file :content "" :literal true)
;;                  (actions/directory config-dir :owner owner :group group)
;;                  (actions/remote-file "cf"
;;                                       :content "[{rabbit, []}]."
;;                                       :literal true)))
;;              (first
;;               (build-actions/build-actions
;;                  {}
;;                  (rabbitmq/settings {:config-file "cf"})
;;                  (rabbitmq/configure {:rabbit {}}))))))
;;     ))
;;     (testing "Customized environment"
;;       (is (= (first
;;               (build-actions/build-actions
;;                  {}
;;                  (actions/directory config-dir :owner owner :group group)
;;                  (actions/remote-file "cf"
;;                                       :content "[{rabbit, []}]."
;;                                       :literal true)
;;                  (actions/directory config-dir :owner owner :group group)
;;                  (actions/remote-file env-file
;;                                       :content "RABBITMQ_LOG_BASE=/opt/rabbitmq/log\nRABBITMQ_MNESIA_BASE=/opt/test/mnesia"
;;                                       :literal true)))
;;              (first
;;               (build-actions/build-actions
;;                  {}
;;                  (rabbitmq/settings {:config-file "cf"
;;                                                   :env {:log-base "/opt/rabbitmq/log"
;;                                                         :mnesia-base "/opt/test/mnesia"}})
;;                  (rabbitmq/configure {:rabbit {}}))))))
;;     (testing "Default cluster"
;;       (is (= (first
;;               (build-actions/build-actions
;;                {}
;;                (actions/directory config-dir :owner owner :group group)
;;                (actions/remote-file "cf"
;;                                     :content "[{rabbit, [{cluster_nodes, ['rabbit@id']}]}]."
;;                                     :literal true)
;;                (actions/directory config-dir :owner owner :group group)
;;                (actions/remote-file env-file :content "" :literal true)))
;;              (first
;;               (build-actions/build-actions
;;                {}
;;                (rabbitmq/settings {:node-count 2
;;                                                 :config-file "cf"})
;;                (rabbitmq/configure {:rabbit {}}))))))))
;;   (testing "ram cluster"
;;       (is (=
;;            (first
;;             (build-actions/build-actions
;;              {}
;;              (actions/remote-file
;;               "cf"
;;               :content "[{rabbit, [{cluster_nodes, ['rabbit@tagnode']}]}]."
;;               :literal true)))
;;            (let [tag-node (test-utils/make-node
;;                            "tagnode" :group-name "tag" :private-ip "12.3.4.6")]
;;              (first
;;               (build-actions/build-actions
;;                {:server {:node node}
;;                 :all-nodes [tag-node node]
;;                 :parameters {:host {:id-12-3-4-5
;;                                     {:rabbitmq {:config-file "cf"
;;                                                 :options {:node-count 2}}}}}}
;; ;;               (rabbitmq/configure :tag {:rabbit {}})))))))))
;;                (rabbitmq/settings (settings-map {:cluster :tag}))
;;                (rabbitmq/configure {:rabbit {}})))))))))

(deftest invocation
  (is (build-actions/build-actions
       {:server
        {:node (test-utils/make-node "id" :private-ips ["12.3.4.5"])}}
       (rabbitmq/server-spec {:node-count 2}))))
