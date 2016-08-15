(ns iwaswhere-web.graph.add
  "Functions for adding new entries."
  (:require [ubergraph.core :as uber]
            [clj-time.coerce :as ctc]
            [clj-time.core :as ct]
            [clj-time.format :as ctf]
            [clojure.string :as s]
            [clojure.set :as set]
            [clojure.tools.logging :as log]
            [iwaswhere-web.utils.misc :as u]
            [iwaswhere-web.graph.query :as gq]
            [iwaswhere-web.specs :as specs]
            [clj-time.coerce :as c]
            [clj-time.core :as t]))

(defn add-hashtags
  "Add hashtag edges to graph for a new entry. When a hashtag exists already,
   an edge to the existing node will be added, otherwise a new hashtag node will
   be created."
  [graph entry]
  (let [tags (set (:tags entry))
        pvt-entry? (seq (set/intersection tags u/private-tags))
        ht-parent (if pvt-entry? :pvt-hashtags :hashtags)
        tag-type (if pvt-entry? :ptag :tag)]
    (reduce (fn [acc tag]
              (let [ltag (s/lower-case tag)]
                (-> acc
                    (uber/add-nodes ht-parent)
                    (uber/add-nodes-with-attrs [{tag-type ltag} {:val tag}])
                    (uber/add-edges
                      [{tag-type ltag} (:timestamp entry)
                       {:relationship :CONTAINS}]
                      [ht-parent {tag-type ltag} {:relationship :IS}]))))
            graph
            tags)))

(defn add-mentions
  "Add mention edges to graph for a new entry. When a mentioned person exists
   already, an edge to the existing node will be added, otherwise a new hashtag
   node will be created."
  [graph entry]
  (let [mentions (:mentions entry)]
    (reduce
      (fn [acc mention]
        (let [lmention (s/lower-case mention)]
          (-> acc
              (uber/add-nodes :mentions)
              (uber/add-nodes-with-attrs [{:mention lmention} {:val mention}])
              (uber/add-edges
                [{:mention lmention} (:timestamp entry) {:relationship :CONTAINS}]
                [:mentions {:mention lmention} {:relationship :IS}]))))
      graph
      mentions)))

(defn add-timeline-tree
  "Adds graph nodes for year, month and day of entry and connects those if they
   don't exist. In any case, connects new entry node to the entry node of the
   matching :timeline/day node."
  [graph entry]
  (let [dt (ctc/from-long (:timestamp entry))
        year (ct/year dt)
        month (ct/month dt)
        year-node {:type :timeline/year :year year}
        month-node {:type :timeline/month :year year :month month}
        day-node {:type :timeline/day :year year :month month :day (ct/day dt)}]
    (-> graph
        (uber/add-nodes year-node month-node day-node)
        (uber/add-edges [year-node month-node]
                        [month-node day-node]
                        [day-node (:timestamp entry) {:relationship :DATE}]))))

(defn add-activity
  "When entry contains activity, adds node for activity if not existing.
   Then connects entry to activity node. Does nothing when entry contains
   no activity."
  [graph entry]
  (if-let [activity (:activity entry)]
    (let [activity-node {:type :activity :name (:name activity)}]
      (-> graph
          (uber/add-nodes :activities activity-node)
          (uber/add-edges
            [:activities activity-node]
            [activity-node (:timestamp entry) {:relationship :CONTAINS}])))
    graph))

(defn add-consumption
  "When entry contains consumption, adds node for consumption type if not
   existing.
   Then connects entry to consumption type node. Does nothing when entry
   contains no consumption."
  [graph entry]
  (if-let [consumption (:consumption entry)]
    (let [consumption-node {:type :consumption-types :name (:name consumption)}]
      (-> graph
          (uber/add-nodes :consumption-types consumption-node)
          (uber/add-edges
            [:consumption-types consumption-node]
            [consumption-node (:timestamp entry) {:relationship :CONTAINS}])))
    graph))

(defn add-parent-ref
  "Adds an edge to parent node when :comment-for key on the entry exists."
  [graph entry]
  (if-let [comment-for (:comment-for entry)]
    (uber/add-edges graph [(:timestamp entry) comment-for {:relationship :COMMENT}])
    graph))

(defn add-linked
  "Add mention edges to graph for a new entry. When a mentioned person exists
   already, an edge to the existing node will be added, otherwise a new hashtag
   node will be created."
  [graph entry]
  (let [linked-entries (:linked-entries entry)]
    (reduce (fn [acc linked-entry]
              (if (uber/has-node? graph linked-entry)
                (uber/add-edges acc
                  [(:timestamp entry) linked-entry {:relationship :LINKED}])
                (do (log/warn "Linked node does not exist, skipping" linked-entry)
                    acc)))
            graph
            linked-entries)))

(defn add-linked-visit
  "Adds linked entry when the entry has been captured during a visit."
  [g entry]
  (let [ts (:timestamp entry)
        ts-dt (c/from-long ts)
        q {:date-string (ctf/unparse (ctf/formatters :year-month-day) ts-dt)}
        same-day-entry-ids (gq/get-nodes-for-day g q)
        same-day-entries (filterv :departure-date
                                  (mapv #(uber/attrs g %) same-day-entry-ids))
        filter-fn (fn [other-entry]
                    (let [{:keys [arrival-ts departure-ts]}
                          (u/visit-timestamps other-entry)]
                      (and (< ts departure-ts) (> ts arrival-ts)
                           (specs/possible-timestamp? departure-ts))))
        matching-visit (first (filterv filter-fn same-day-entries))]
    (if matching-visit
      (let [linked-ts (:timestamp matching-visit)]
        (uber/add-edges g [(:timestamp entry) linked-ts
                           {:relationship :LINKED}]))
      g)))

(defn remove-unused-tags
  "Checks for orphan tags and removes them. Orphan tags would occur when
   deleting the last entry that contains a specific tag. Takes the state, a list
   of tags, and the tag type, such as :tags or :mentions."
  [state tags k]
  (reduce (fn [state t]
            (if (empty? (uber/find-edges
                          (:graph state) {:src {k t} :relationship :CONTAINS}))
              (update-in state [:graph] #(uber/remove-nodes % {k t}))
              state))
          state
          tags))

(defn remove-node
  "Removes node from graph and sorted set if node for specified timestamp
   exists."
  [current-state ts]
  (let [g (:graph current-state)]
    (if (uber/has-node? g ts)
      (let [entry (uber/attrs g ts)]
        (-> current-state
            (update-in [:graph] #(uber/remove-nodes % ts))
            (update-in [:sorted-entries] disj ts)
            (remove-unused-tags (:mentions entry) :mention)
            (remove-unused-tags (:tags entry) :tag)))
      current-state)))

(defn add-node
  "Adds node to both graph and the sorted set, which maintains the entries
   sorted by timestamp."
  [current-state ts entry]
  (let [graph (:graph current-state)
        old-entry (when (uber/has-node? graph ts) (uber/attrs graph ts))
        merged (merge old-entry entry)]
    (-> current-state
        (remove-node ts)
        (update-in [:graph] #(uber/add-nodes-with-attrs % [ts merged]))
        (update-in [:graph] add-hashtags entry)
        (update-in [:graph] add-mentions entry)
        (update-in [:graph] add-linked entry)
        (update-in [:graph] add-timeline-tree entry)
        (update-in [:graph] add-activity entry)
        (update-in [:graph] add-consumption entry)
        (update-in [:graph] add-linked-visit entry)
        (update-in [:graph] add-parent-ref entry)
        (update-in [:sorted-entries] conj ts))))