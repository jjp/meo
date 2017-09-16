(ns iwaswhere-electron.renderer.exec
  (:require [taoensso.timbre :as timbre :refer-macros [info]]
            [electron :refer [ipcRenderer]]
            [cljs.spec.alpha :as s]
            [clojure.string :as str]))

(s/def :exec/js string?)

(defn state-fn [put-fn]
  (let [webview (.querySelector js/document "webview")
        web-contents (.getWebContents webview)
        redirect (fn [e callback]
                   (let [url (.-url e)]
                     (put-fn [:app/open-external url])
                     (.preventDefault e)
                     (.stopPropagation e)))]
    (.addEventListener webview "will-navigate" redirect)
    (.addEventListener webview "new-window" redirect)
    (info "Starting EXEC Component")
    {:state (atom {:web-contents web-contents})}))

(defn exec-js [{:keys [current-state msg-payload]}]
  (info "EXEC:" msg-payload)
  (let [webview (.querySelector js/document "webview")
        web-contents (.getWebContents webview)]
    (when web-contents
      (.executeJavaScript web-contents msg-payload))
    {}))

(defn relay-msg [{:keys [current-state msg-type msg-meta msg-payload]}]
  (let [webview (.querySelector js/document "webview")
        web-contents (.getWebContents webview)
        serialized (pr-str [msg-type {:msg-payload msg-payload :msg-meta msg-meta}])
        js (str "iwaswhere_web.ui.menu.relay('" serialized "')")]
    (info "RENDERER relaying" (str msg-type) (str msg-payload))
    (when web-contents
      (.executeJavaScript web-contents js)))
  {})

(defn cmp-map [cmp-id relay-types]
  (let [relay-map (zipmap relay-types (repeat relay-msg))]
    {:cmp-id      cmp-id
     :state-fn    state-fn
     :handler-map (merge relay-map {:exec/js exec-js})}))
