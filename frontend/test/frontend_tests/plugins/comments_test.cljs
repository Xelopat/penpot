;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.plugins.comments-test
  (:require
   [app.main.data.comments :as dc]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.plugins.comments :as comments]
   [app.plugins.page :as page]
   [app.plugins.register :as r]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]))

(def ^:private plugin-id "00000000-0000-0000-0000-000000000000")

(t/deftest ^:async comment-threads-respect-page-and-visibility-filters
  (let [file-id    (random-uuid)
        page-id    (random-uuid)
        profile-id (random-uuid)
        proxy      (page/page-proxy plugin-id file-id page-id)
        threads    [{:id (random-uuid) :seqn 1 :page-id page-id
                     :is-resolved false :participants #{profile-id}}
                    {:id (random-uuid) :seqn 2 :page-id page-id
                     :is-resolved true :participants #{(random-uuid)}}
                    {:id (random-uuid) :seqn 3 :page-id (random-uuid)
                     :is-resolved false :participants #{profile-id}}]]
    (await
     (mock/with-mocks*
       {st/state           (atom {:profile-id profile-id})
        r/check-permission (constantly true)
        rp/cmd!            (mock/stub
                            (fn [command params]
                              (t/is (= :get-comment-threads command))
                              (t/is (= file-id (:file-id params)))
                              (->> (rx/of threads) (rx/observe-on :async))))}
       (let [visible (await (.findCommentThreads proxy))
             all     (await (.findCommentThreads proxy #js {:showResolved true}))
             yours   (await (.findCommentThreads proxy #js {:showResolved true :onlyYours true}))]
         (t/is (= [1] (mapv #(.-seqNumber %) visible)))
         (t/is (= [1 2] (mapv #(.-seqNumber %) all)))
         (t/is (= [1] (mapv #(.-seqNumber %) yours))))))))

(t/deftest comment-thread-remove-allows-the-owner
  (t/async done
    (let [owner-id  (random-uuid)
          file-id   (random-uuid)
          page-id   (random-uuid)
          thread-id (random-uuid)
          emitted   (atom nil)
          thread    (comments/comment-thread-proxy
                     plugin-id
                     file-id
                     page-id
                     {:id thread-id :owner-id owner-id})]
      (set! st/state (atom {:profile {:id owner-id}}))
      (mock/with-mocks
        {r/check-permission (constantly true)
         dc/delete-comment-thread-on-workspace
         (mock/stub (fn [params callback]
                      (callback)
                      [:delete-thread params]))
         st/emit! (mock/stub (fn [event] (reset! emitted event)))}
        (fn [done']
          (let [result (.remove thread)]
            (t/is (instance? js/Promise result))
            (t/is (= [:delete-thread {:id thread-id}] @emitted))
            (done')))
        done))))

(t/deftest page-remove-comment-thread-emits-delete-event
  (t/async done
    (let [file-id   (random-uuid)
          page-id   (random-uuid)
          thread-id (random-uuid)
          emitted   (atom nil)
          page      (page/page-proxy plugin-id file-id page-id)
          thread    (comments/comment-thread-proxy
                     plugin-id
                     file-id
                     page-id
                     {:id thread-id :owner-id (random-uuid)})]
      (mock/with-mocks
        {r/check-permission (constantly true)
         dc/delete-comment-thread-on-workspace
         (mock/stub (fn [params callback]
                      (callback)
                      [:delete-thread params]))
         st/emit! (mock/stub (fn [event] (reset! emitted event)))}
        (fn [done']
          (let [result (.removeCommentThread page thread)]
            (t/is (instance? js/Promise result))
            (t/is (= [:delete-thread {:id thread-id}] @emitted))
            (done')))
        done))))
