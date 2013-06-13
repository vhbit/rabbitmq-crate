(defproject com.palletops/rabbitmq-crate "0.8.0-SNAPSHOT"
  :description "Pallet crate to install, configure and use RabbitMQ"
  :url "http://palletops.com"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:url "git@github.com:pallet/runit-crate.git"}

  :dependencies [[org.clojure/clojure "1.4.0"]
                 [com.palletops/pallet "0.8.0-SNAPSHOT"]
                 [com.palletops/crates "0.1.0"]
                 [com.palletops/iptables-crate "0.8.0-SNAPSHOT"]
                 [com.palletops/upstart-crate "0.8.0-alpha.2"]]
  :resource {:resource-paths ["doc-src"]
             :target-path "target/classes/pallet_crate/rabbitmq_crate/"
             :includes [#"doc-src/USAGE.*"]}
  :prep-tasks ["resource" "crate-doc"])
