;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.plugins.file-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.exports.files :as exports.files]
   [app.main.data.persistence :as dps]
   [app.main.store :as st]
   [app.plugins.file :as file]
   [app.plugins.utils :as u]
   [app.util.http :as http]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]))

(t/deftest ^:async file-export-waits-for-save-and-returns-archive-bytes
  (doseq [format ["penpot" "zip"]
          mode   ["all" "merge" "detach"]]
    (let [file-id   (uuid/next)
          data      {:id file-id :name "QA"}
          proxy     (file/file-proxy "00000000-0000-0000-0000-000000000000" file-id)
          persisted (atom false)
          options   (atom nil)]
      (await
       (mock/with-mocks*
         {u/locate-file                   (mock/stub (fn [_] data))
          st/emit!                        (mock/stub (fn [_]))
          dps/unsaved-changes?             (mock/stub (fn [_ _] false))
          dps/force-persist-files-and-wait (mock/stub
                                            (fn [_ _]
                                              (->> (rx/of :qa-persist)
                                                   (rx/observe-on :async)
                                                   (rx/tap (fn [_] (reset! persisted true))))))
          exports.files/export-files      (fn [& {:keys [files type]}]
                                            (t/is @persisted "save completes before export")
                                            (reset! options {:files files :type type})
                                            (->> (rx/of {:uri "/qa-archive"})
                                                 (rx/observe-on :async)))
          http/send!                      (mock/stub
                                           (fn [request]
                                             (t/is (= "/qa-archive" (:uri request)))
                                             (->> (rx/of {:body (.-buffer (js/Uint8Array. #js [80 75 3 4]))})
                                                  (rx/observe-on :async))))}
         (let [result (await (.export proxy format mode))]
           (t/is (instance? js/Uint8Array result))
           (t/is (= [80 75 3 4] (vec (js/Array.from result))))
           (t/is (= {:files [data] :type (keyword mode)} @options))))))))

(t/deftest ^:async file-export-rejects-server-errors
  (let [file-id (uuid/next)
        proxy   (file/file-proxy "00000000-0000-0000-0000-000000000000" file-id)]
    (await
     (mock/with-mocks*
       {u/locate-file                   (mock/stub (fn [_] {:id file-id :name "QA"}))
        dps/unsaved-changes?             (mock/stub (fn [_ _] false))
        dps/force-persist-files-and-wait (mock/stub (fn [_ _] (rx/empty)))
        exports.files/export-files      (fn [& _]
                                          (->> (rx/of {:error {:code :export-failed}})
                                               (rx/observe-on :async)))}
       (let [error (await (.catch (.export proxy "penpot") identity))]
         (t/is (= :export-file (:type (ex-data error)))))))))

(t/deftest ^:async file-export-rejects-unsaved-changes
  (let [file-id (uuid/next)
        proxy   (file/file-proxy "00000000-0000-0000-0000-000000000000" file-id)]
    (await
     (mock/with-mocks*
       {u/locate-file                   (mock/stub (fn [_] {:id file-id :name "QA"}))
        dps/unsaved-changes?             (mock/stub (fn [_ _] true))
        dps/force-persist-files-and-wait (mock/stub
                                          (fn [_ _]
                                            (->> (rx/empty) (rx/observe-on :async))))}
       (let [error (await (.catch (.export proxy "penpot") identity))]
         (t/is (= :export-unsaved-changes (:type (ex-data error)))))))))

(t/deftest file-version-created-at-returns-stored-date
  (let [created-at (js/Date.)
        version    (file/file-version-proxy
                    "00000000-0000-0000-0000-000000000000"
                    (random-uuid)
                    {}
                    {:id (random-uuid)
                     :label "Version"
                     :created-at created-at})]
    (t/is (identical? created-at (.-createdAt version)))))
