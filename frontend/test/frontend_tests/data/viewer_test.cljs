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
   [app.main.ui.viewer.scroll-box :as vsb]
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

(defn- box-objects
  "Screen > carousel (horizontal scroll) > three cards, one with a nested
  scrolling board that must scroll with the carousel."
  []
  (let [rect (fn [x y w h] {:x x :y y :width w :height h :x1 x :y1 y :x2 (+ x w) :y2 (+ y h)})
        id   #(uuid/custom 3 %)]
    {(id 1) {:id (id 1) :type :frame :parent-id uuid/zero :shapes [(id 2)] :selrect (rect 0 0 1440 900)}
     (id 2) {:id (id 2) :type :frame :parent-id (id 1) :shapes [(id 3) (id 4) (id 5)]
             :scroll-overflow :horizontal :scroll-snap true :fills [{:fill-color "#fff"}]
             :interactions [{:event-type :after-delay :action-type :navigate}
                            {:event-type :click :action-type :navigate}]
             :selrect (rect 100 100 600 300)}
     (id 3) {:id (id 3) :type :rect :parent-id (id 2) :selrect (rect 100 100 400 300)}
     (id 4) {:id (id 4) :type :rect :parent-id (id 2) :selrect (rect 520 100 400 300)}
     (id 5) {:id (id 5) :type :frame :parent-id (id 2) :shapes [] :scroll-overflow :vertical
             :selrect (rect 940 100 400 320)}}))

(t/deftest scroll-boxes
  (let [objects (box-objects)
        id      #(uuid/custom 3 %)
        board   (get objects (id 2))]

    (t/testing "only the outermost scrolling boards get a scroll box"
      (t/is (= #{(id 2)} (vsb/scroll-board-ids objects (id 1))))
      (t/is (= #{(id 3) (id 4) (id 5)} (vsb/scroll-content-ids objects #{(id 2)}))))

    (t/testing "hidden boards and boards without overflow do not scroll"
      (t/is (empty? (vsb/scroll-board-ids (assoc-in objects [(id 2) :hidden] true) (id 1))))
      (t/is (= #{(id 5)} (vsb/scroll-board-ids (update objects (id 2) dissoc :scroll-overflow) (id 1)))))

    (t/testing "the content extends to the farthest child"
      (t/is (= [1240 320] (vsb/content-size objects board))))

    (t/testing "snap points align each direct child with the board"
      (t/is (= {:x #{0 420 840} :y #{0}} (vsb/snap-points objects board))))

    (t/testing "the board in the scroll box keeps only its content"
      (let [prepared (vsb/prepare-board board)]
        (t/is (= [] (:fills prepared)))
        (t/is (true? (:show-content prepared)))
        (t/is (= [:click] (mapv :event-type (:interactions prepared))))))))
