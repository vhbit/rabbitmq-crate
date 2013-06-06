(ns pallet.crate.erlang-config
  (:require [clojure.string :as string]))

(defmulti erlang-config-format class)
(defmethod erlang-config-format :default
  [x]
  (str x))

(defmethod erlang-config-format clojure.lang.Named
  [x]
  (name x))

(defmethod erlang-config-format java.lang.String
  [x]
  (str "'" x "'"))

(defmethod erlang-config-format java.util.Map$Entry
  [x]
  (str
   "{" (erlang-config-format (key x)) ", " (erlang-config-format (val x)) "}"))

(defmethod erlang-config-format clojure.lang.ISeq
  [x]
  (str "[" (string/join "," (map erlang-config-format x)) "]"))

(defmethod erlang-config-format clojure.lang.IPersistentMap
  [x]
  (str "[" (string/join "," (map erlang-config-format x)) "]"))

(defn as-string [m]
  (str (erlang-config-format m) "."))
