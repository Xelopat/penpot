;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.viewer-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.uuid :as uuid]
   [app.main.data.viewer :as dv]
   [app.main.router :as rt]
   [beicon.v2.core :as rx]
   [cljs.test :as t]
   [potok.v2.core :as ptk]))

(def ^:private page-id
  (uuid/custom 1 1))

(defn- base-state
  "Build a minimal viewer state with the given frames and query-params."
  [{:keys [frames index]}]
  {:route {:params {:query {:page-id (str page-id)
                            :index   (str index)}}}
   :viewer {:pages {page-id {:frames frames}}}
   :viewer-local {:viewport-size {:width 1000 :height 800}}})

(t/deftest zoom-to-fit-clamps-out-of-bounds-index
  (t/testing "index exceeds frame count"
    (let [state (base-state {:frames [{:selrect {:width 100 :height 100}}]
                             :index  1})
          result (ptk/update dv/zoom-to-fit state)]
      (t/is (= (get-in result [:viewer-local :zoom-type]) :fit))
      (t/is (number? (get-in result [:viewer-local :zoom])))))

  (t/testing "index is zero with single frame (normal case)"
    (let [state (base-state {:frames [{:selrect {:width 100 :height 100}}]
                             :index  0})
          result (ptk/update dv/zoom-to-fit state)]
      (t/is (= (get-in result [:viewer-local :zoom-type]) :fit))
      (t/is (number? (get-in result [:viewer-local :zoom])))))

  (t/testing "index within valid range with multiple frames"
    (let [state (base-state {:frames [{:selrect {:width 100 :height 100}}
                                      {:selrect {:width 200 :height 200}}]
                             :index  1})
          result (ptk/update dv/zoom-to-fit state)]
      (t/is (= (get-in result [:viewer-local :zoom-type]) :fit))
      (t/is (number? (get-in result [:viewer-local :zoom]))))))

(t/deftest zoom-to-fill-clamps-out-of-bounds-index
  (t/testing "index exceeds frame count"
    (let [state (base-state {:frames [{:selrect {:width 100 :height 100}}]
                             :index  1})
          result (ptk/update dv/zoom-to-fill state)]
      (t/is (= (get-in result [:viewer-local :zoom-type]) :fill))
      (t/is (number? (get-in result [:viewer-local :zoom])))))

  (t/testing "index is zero with single frame (normal case)"
    (let [state (base-state {:frames [{:selrect {:width 100 :height 100}}]
                             :index  0})
          result (ptk/update dv/zoom-to-fill state)]
      (t/is (= (get-in result [:viewer-local :zoom-type]) :fill))
      (t/is (number? (get-in result [:viewer-local :zoom])))))

  (t/testing "index within valid range with multiple frames"
    (let [state (base-state {:frames [{:selrect {:width 100 :height 100}}
                                      {:selrect {:width 200 :height 200}}]
                             :index  1})
          result (ptk/update dv/zoom-to-fill state)]
      (t/is (= (get-in result [:viewer-local :zoom-type]) :fill))
      (t/is (number? (get-in result [:viewer-local :zoom]))))))

(defn- watch-events
  "Collect the events an event's watch emits synchronously."
  [event state]
  (let [out (atom [])]
    (some-> (ptk/watch event state nil)
            (rx/subscribe #(swap! out conj %)))
    @out))

(defn- zoom-state
  "Build a viewer state with the given `:zoom` query param and zoom type."
  [zoom-param zoom-type]
  {:route {:params {:query (cond-> {:page-id (str page-id) :index "0"}
                             (some? zoom-param)
                             (assoc :zoom zoom-param))}}
   :viewer-local (cond-> {}
                   (some? zoom-type)
                   (assoc :zoom-type zoom-type))})

(t/deftest update-zoom-querystring-does-not-navigate-when-url-already-matches
  (t/testing "zoom type already described by the query string"
    (t/is (empty? (watch-events dv/update-zoom-querystring
                                (zoom-state "fit" :fit)))))

  (t/testing "no zoom type and no zoom query param"
    (t/is (empty? (watch-events dv/update-zoom-querystring
                                (zoom-state nil nil))))))

(t/deftest update-zoom-querystring-navigates-when-zoom-changes
  (t/testing "zoom type differs from the query string"
    (let [events (watch-events dv/update-zoom-querystring
                               (zoom-state "fit" :fill))
          {:keys [id params options]} (some-> (first events) deref)]
      (t/is (= 1 (count events)))
      (t/is (= :viewer id))
      (t/is (= :fill (:zoom params)))
      (t/is (true? (::rt/replace options)))))

  (t/testing "zoom query param absent, other params preserved"
    (let [events (watch-events dv/update-zoom-querystring
                               (zoom-state nil :fit))
          {:keys [params]} (some-> (first events) deref)]
      (t/is (= 1 (count events)))
      (t/is (= :fit (:zoom params)))
      (t/is (= (str page-id) (:page-id params)))
      (t/is (= "0" (:index params)))))

  (t/testing "zoom type cleared drops the query param"
    (let [events (watch-events dv/update-zoom-querystring
                               (zoom-state "fit" nil))
          {:keys [params]} (some-> (first events) deref)]
      (t/is (= 1 (count events)))
      (t/is (not (contains? params :zoom))))))

(defn- overlay-state
  [frames overlays]
  {:route {:query-params {:page-id (str page-id)}}
   :viewer {:pages {page-id {:all-frames frames}}}
   :viewer-overlays overlays})

(def ^:private dissolve
  {:animation-type :dissolve :duration 200 :easing :ease-out})

(t/deftest swap-overlay-replaces-without-stacking
  (let [tab-a  {:id (uuid/custom 2 1)}
        tab-b  {:id (uuid/custom 2 2)}
        open-a {:id (:id tab-a) :frame tab-a :anchor :anchor}]

    (t/testing "without animation the replaced overlay is removed at once"
      (let [state  (overlay-state [tab-a tab-b] [open-a])
            result (ptk/update (dv/swap-overlay [(:id tab-a)] (:id tab-b) (gpt/point 0 0)
                                                [:top :left] false false nil false
                                                {:anchor :anchor})
                               state)]
        (t/is (= [(:id tab-b)] (mapv :id (:viewer-overlays result))))
        (t/is (= :anchor (-> result :viewer-overlays first :anchor)))))

    (t/testing "with animation the replaced overlay stays below until it ends"
      (let [state  (overlay-state [tab-a tab-b] [open-a])
            result (ptk/update (dv/swap-overlay [(:id tab-a)] (:id tab-b) (gpt/point 0 0)
                                                [:top :left] false false dissolve false nil)
                               state)
            done   (ptk/update (dv/complete-animation) result)]
        (t/is (= [(:id tab-a) (:id tab-b)] (mapv :id (:viewer-overlays result))))
        (t/is (= (:id tab-b) (-> result :viewer-overlays first :replaced-by)))
        (t/is (= {:kind :swap-overlay
                  :overlay-id (:id tab-b)
                  :from-overlay-id (:id tab-a)
                  :animation dissolve}
                 (get-in result [:viewer-animations (:id tab-b)])))
        (t/is (= [(:id tab-b)] (mapv :id (:viewer-overlays done))))))

    (t/testing "swapping to an overlay already shown only drops the others"
      (let [state  (overlay-state [tab-a tab-b] [open-a {:id (:id tab-b) :frame tab-b}])
            result (ptk/update (dv/swap-overlay [(:id tab-a)] (:id tab-b) (gpt/point 0 0)
                                                [:top :left] false false dissolve false nil)
                               state)]
        (t/is (= [(:id tab-b)] (mapv :id (:viewer-overlays result))))
        (t/is (nil? (:viewer-animations result)))))

    (t/testing "a toggle ignores overlays that are being replaced"
      (let [state  (overlay-state [tab-a] [(assoc open-a :replaced-by (:id tab-b))])
            result (ptk/update (dv/toggle-overlay (:id tab-a) (gpt/point 0 0) [:top :left]
                                                  false false nil false)
                               state)]
        (t/is (= 2 (count (:viewer-overlays result))))))))
