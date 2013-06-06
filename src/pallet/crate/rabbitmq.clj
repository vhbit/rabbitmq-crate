(ns pallet.crate.rabbitmq
  (:require
   [clojure.tools.logging :refer [debugf]]
   [pallet.api :refer [plan-fn] :as api]
   [pallet.actions :as actions]
   [pallet.node :as node]
   [pallet.crate.etc-default :as etc-default]
   [pallet.crate.etc-hosts :as etc-hosts]
   [pallet.crate.iptables :as iptables]
   [pallet.crate :refer [defplan assoc-settings get-settings service-phases] :as crate]
   [pallet.utils :refer [apply-map]]
   [clojure.string :as string]
   [pallet.crate.erlang-config :as erlang-config]
   [pallet.stevedore :refer [fragment]]
   [pallet.script.lib :refer [file config-root log-root]]
   [pallet.version-dispatch :refer [defmethod-version-plan
                                    defmulti-version-plan]]
   [pallet.crate.service :refer [supervisor-config supervisor-config-map] :as service]
   [pallet.crate-install :as crate-install]
   [pallet.crate.nohup]))


(def ^{:doc "Flag for recognising changes to configuration"}
  rabbitmq-config-changed-flag "rabbitmq-config")

;;; # Settings
(defn service-name
  "Return a service name for rabbitmq."
  [{:keys [instance-id] :as options}]
  (str "rabbitmq" (when instance-id (str "-" instance-id))))

(defn default-settings [options]
  {:user "rabbitmq"
   :group "rabbitmq"
   :owner "rabbitmq"
   :config-file "/etc/rabbitmq/rabbitmq.config"
   :env-file "/etc/rabbitmq/rabbitmq-env.conf"
   :config-dir (fragment (file (config-root) "rabbitmq"))
   :log-dir (fragment (file (log-root) "rabbitmq"))
   :supervisor :nohup
   :nohup {:process-name "rabbitmq-server"}
   :service-name (service-name options)
   :node-count 1
   :env {}
   :config {:rabbit {}}})

(def ^{:doc "rabbitmq environment settings"} env-keys
  {:mnesia-base :RABBITMQ_MNESIA_BASE
   :mnesia-dir :RABBITMQ_MNESIA_DIR
   :base :RABBITMQ_BASE
   :log-base :RABBITMQ_LOG_BASE
   :logs :RABBITMQ_LOGS
   :sasl-logs :RABBITMQ_SASL_LOGS
   :plugins-dir :RABBITMQ_PLUGINS_DIR
   :enabled-plugins-file :RABBITMQ_ENABLED_PLUGINS_FILE
   :plugins-expand-dir :RABBITMQ_PLUGINS_EXPAND_DIR
   :nodename :RABBITMQ_NODENAME
   :node-ip-addres :RABBITMQ_NODE_IP_ADDRESS
   :node-port :RABBITMQ_NODE_PORT
   :config-file :RABBITMQ_CONFIG_FILE
   :server-start-args :RABBITMQ_SERVER_START_ARGS
   :multi-start-args :RABBITMQ_MULTI_START_ARGS
   :ctl-erl-args :RABBITMQ_CTL_ERL_ARGS
   :server-erl-args :RABBITMQ_SERVER_ERL_ARGS})

(defn run-command
  "Return a script command to run rabbitmq."
  [{:keys [home user config-dir] :as settings}]
  (fragment (file "rabbitmq-server")))

;;; At the moment we just have a single implementation of settings,
;;; but this is open-coded.
(defmulti-version-plan settings-map [version settings])

(defmethod-version-plan
    settings-map {:os :linux}
    [os os-version version settings]
  (cond
   (:install-strategy settings) settings
   :else  (assoc settings
            :install-strategy ::packages
            :packages ["rabbitmq-server"])))


(defmethod supervisor-config-map [:rabbitmq :runit]
  [_ {:keys [run-command service-name user] :as settings} options]
  {:service-name service-name
   :run-file {:content (str "#!/bin/sh\nexec chpst " run-command)}})

(defmethod supervisor-config-map [:rabbitmq :upstart]
  [_ {:keys [run-command service-name user] :as settings} options]
  {:service-name service-name
   :exec run-command
   :setuid user})

(defmethod supervisor-config-map [:rabbitmq :nohup]
  [_ {:keys [run-command service-name user] :as settings} options]
  {:service-name service-name
   :run-file {:content run-command}
   :user user})


(defplan settings
  "Settings for rabbitmq"
  [{:keys [user owner group dist dist-urls version instance-id]
    :as settings}
   & {:keys [instance-id] :as options}]
  (let [settings (merge (default-settings options) settings)
        settings (settings-map (:version settings) settings)
        settings (update-in settings [:run-command]
                            #(or % (run-command settings)))]
    (assoc-settings :rabbitmq settings {:instance-id instance-id})
    (supervisor-config :rabbitmq settings (or options {}))))

;;; # User
(defplan user
  "Create the rabbitmq user"
  [{:keys [instance-id] :as options}]
  (let [{:keys [user owner group home]} (get-settings :rabbitmq options)]
    (actions/group group :system true)
    (when (not= owner user)
      (actions/user owner :group group :system true))
    (actions/user
     user :group group :system true :create-home true :shell :bash)))

;;; # Configuration
(defplan config-file
  "Helper to write config files"
  [{:keys [owner group config-dir] :as settings} filename file-source]
  (actions/directory config-dir :owner owner :group group)
  (apply-map
   actions/remote-file (fragment (file ~filename))
   :flag-on-changed rabbitmq-config-changed-flag
   :owner owner :group group
   file-source))

(defn- cluster-nodes
  "Create a node list for the specified nodes"
  [node-name nodes]
  (map
   (fn cluster-node-name [node]
     (str node-name "@" (node/hostname node)))
   nodes))

(defn- cluster-nodes-for-group
  "Create a node list for the specified group"
  [group]
  nil)
  ;; TODO: find a way to express it in 0.8 terms
  ;; (let [nodes (crate/nodes-in-group group)]
  ;;   (assert (seq nodes))
  ;;   (cluster-nodes
  ;;    (parameter/get-for
  ;;     session
  ;;     [:host (keyword (compute/id (first nodes))) :rabbitmq :options :node-name]
  ;;     "rabbit")
  ;;    nodes)))

(defn- default-cluster-nodes
  [options]
  (cluster-nodes
   (:node-name options "rabbit")
   (crate/nodes-in-group)))

(defn env-as-string [env]
  (string/join "\n"
               (map (fn [[k v]]
                      (format "%s=%s" (name (get env-keys k)) v))
                    (select-keys env (keys env-keys)))))

(defplan configure
  "Write all config files"
  [{:keys [instance-id] :as options}]
  (let [settings (get-settings :rabbitmq options)
        cluster (:cluster settings)
        cluster-nodes (when cluster (cluster-nodes-for-group cluster))
        cluster-nodes (or cluster-nodes
                          (if-let [node-count (:node-count settings)]
                            (when (> node-count 1)
                              (default-cluster-nodes settings))))
        config (:config settings)
        config-str (erlang-config/as-string
                     (if cluster-nodes
                       (assoc-in config [:rabbit :cluster_nodes] cluster-nodes)
                       config))
        env-str (env-as-string (:env settings))]
    (debugf "configure %s %s" settings options)
    (config-file settings (:env-file settings) {:content env-str})
    (config-file settings (:config-file settings)
                 {:content config-str})))

;;; # Install
(defplan install
  "Install rabbitmq."
  [{:keys [instance-id]}]
  (let [{:keys [install-strategy owner group log-dir] :as settings}
        (get-settings :rabbitmq {:instance-id instance-id})]
    (crate-install/install :rabbitmq instance-id)
    (when log-dir
      (actions/directory log-dir :owner owner :group group :mode "0755"))))

;;; # Run
(defplan service
  "Run the rabbitmq service."
  [& {:keys [action if-flag if-stopped instance-id]
      :or {action :manage}
      :as options}]
  (let [{:keys [supervision-options] :as settings}
        (get-settings :rabbitmq {:instance-id instance-id})]
    (service/service settings (merge supervision-options
                                     (dissoc options :instance-id)))))

(defn server-spec
  [settings & {:keys [instance-id] :as options}]
  (api/server-spec
   :phases
   (merge {:settings (plan-fn (pallet.crate.rabbitmq/settings (merge settings options)))
           :install (plan-fn
                      (user options)
                      (install options))
           :configure (plan-fn
                        (configure options)
                        (apply-map service :action :enable options))
           :run (plan-fn
                  (apply-map service :action :start options))}
          (service-phases :rabbitmq options service))))


(defn iptables-accept
  "Accept rabbitmq connectios, by default on port 5672"
  ([] (iptables-accept 5672))
  ([port]
     (iptables/iptables-accept-port port)))

(defn iptables-accept-status
  "Accept rabbitmq status connections, by default on port 55672"
  ([] (iptables-accept 55672))
  ([port]
     (iptables/iptables-accept-port port)))

(defn password
  "Change rabbitmq password."
  [user password]
  (do
   (actions/exec-checked-script
    "Change RabbitMQ password"
    ("rabbitmqctl" change_password ~user ~password))))