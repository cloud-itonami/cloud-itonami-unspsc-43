(ns itad.advisor
  "TriageAdvisor -- the *contained intelligence node* for the
  independent IT asset recovery and e-waste refurbishment coordination
  actor.

  It normalizes device-intake patches (category/functional-status/
  wipe-level/battery-status), drafts a data-wipe scheduling proposal
  against a device, drafts a battery-hazard/unverified-sensitive-data
  concern flag, and drafts a resell/refurbish/certified-recycle
  disposition proposal against a device. CRITICAL: it is a smart-but-
  untrusted advisor. It returns a *proposal* (with a rationale + the
  fields it cited), never a committed record and NEVER a real
  destruction-equipment actuation (shredder/degausser/wipe-station) or
  R2v3-conformance/data-destruction certification -- see README `What
  this actor does NOT do`. Every output is censored downstream by
  `itad.governor` before anything touches the SSoT.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- informational only, NOT trusted
                                 ; by the governor for any ground-truth
                                 ; check (see `itad.governor`)
     :cites      [kw|str ..]    ; fields the advisor used
     :effect     kw             ; how a commit would mutate the SSoT --
                                 ; ALWAYS one of the closed
                                 ; #{:device/upsert :wipe/schedule
                                 ; :safety-concern/flag
                                 ; :disposition/propose} propose-shaped
                                 ; effects, NEVER a direct destruction-
                                 ; equipment-actuation effect
     :stake      kw|nil         ; :coordination/safety-concern | nil
     :confidence 0..1}

  CRITICAL invariant this advisor upholds: every request it is asked to
  route MUST itself carry `:effect :propose` (the request-level
  contract every caller of this actor agrees to) -- `itad.governor`
  HARD-holds any request that doesn't, so a mis-wired caller can never
  reach a commit path even if this advisor were compromised."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [itad.registry :as registry]
            [itad.store :as store]
            [langchain.model :as model]))

(defn- log-device-intake
  "Device-intake upsert -- the advisor only normalizes/validates the
  patch; it does not invent the device's category, functional-status,
  wipe-level, battery-status, or verification status. High confidence,
  low stakes -- administrative logging, not an operational decision."
  [_db {:keys [patch]}]
  {:summary    (str "端末受入記録更新: " (pr-str (keys patch)))
   :rationale  "入力patchの正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :device/upsert
   :value      patch
   :stake      nil
   :confidence 0.95})

(defn- schedule-data-wipe
  "Draft a data-wipe scheduling proposal against a device. The
  advisor reports what it can see (device verified?/registered?) in
  its rationale, but `itad.governor` NEVER trusts this report -- it
  independently re-derives verified?/registered? from the device's
  own stored fields before any commit is possible."
  [db {:keys [subject value]}]
  (let [device-id (:device-id value)
        d (store/device db device-id)
        ready? (and d (registry/device-ready? d))]
    {:summary    (str subject " 向けデータ消去予定提案 (target=" (:wipe-target-level value) ")"
                      (when d (str " device=" device-id)))
     :rationale  (if d
                   (str "device-verified?=" (registry/device-verified? d)
                        " device-registered?=" (registry/device-registered? d)
                        " actuate-equipment?=" (boolean (:actuate-equipment? value)))
                   (str device-id " が見つかりません"))
     :cites      (if d [device-id] [])
     :effect     :wipe/schedule
     :value      value
     :stake      nil
     :confidence (if (and ready? (not (:actuate-equipment? value))) 0.9 0.3)}))

(defn- flag-safety-concern
  "Draft a battery-hazard or unverified-sensitive-data concern. ALWAYS
  `:stake :coordination/safety-concern` -- a safety concern is NEVER a
  proposal the advisor may quietly downgrade to low-stakes, and it is
  never gated on the referenced device being verified (a concern can
  be raised about ANY device, verified or not -- see README `What this
  actor does NOT do` re: never blocking safety-relevant reporting on
  an administrative technicality). See `itad.phase`: no phase ever
  adds this op to a phase's `:auto` set; `itad.governor` also always
  escalates on `:coordination/safety-concern`. Two independent layers
  agree, deliberately."
  [db {:keys [subject value]}]
  (let [device-id (:device-id value)
        d (and device-id (store/device db device-id))]
    {:summary    (str subject " 向け安全懸念報告 (" (:concern value) ")"
                      (when d (str " device=" device-id)))
     :rationale  (str "concern=" (:concern value) " description=" (:description value))
     :cites      (if d [device-id] [])
     :effect     :safety-concern/flag
     :value      value
     :stake      :coordination/safety-concern
     :confidence 0.9}))

(defn- propose-disposition
  "Draft a resell/refurbish/certified-recycle disposition proposal
  against a device. The advisor passes through the caller's own
  claimed disposition -- it does NOT invent one, and `itad.governor`
  NEVER trusts it: it independently recomputes whether the device's
  own recorded wipe-level is sufficient for a data-bearing disposition
  before any commit is possible."
  [db {:keys [subject value]}]
  (let [device-id (:device-id value)
        d (store/device db device-id)
        ready? (and d (registry/device-ready? d))
        wipe-sufficient? (and d (registry/wipe-level-sufficient-for-disposition?
                                 d (:disposition value)))]
    {:summary    (str subject " 向け処分方法提案 ("
                      (:disposition value) ")"
                      (when d (str " device=" device-id)))
     :rationale  (if d
                   (str "device-verified?=" (registry/device-verified? d)
                        " device-registered?=" (registry/device-registered? d)
                        " wipe-level=" (:wipe-level d)
                        " wipe-sufficient?=" wipe-sufficient?)
                   (str device-id " が見つかりません"))
     :cites      (if d [device-id] [])
     :effect     :disposition/propose
     :value      value
     :stake      nil
     :confidence (if (and ready? wipe-sufficient?) 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :effect :propose :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :log-device-intake      (log-device-intake db request)
    :schedule-data-wipe      (schedule-data-wipe db request)
    :flag-safety-concern     (flag-safety-concern db request)
    :propose-disposition     (propose-disposition db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたはITAD (IT資産処分) 事業者の助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:device/upsert|:wipe/schedule|"
       ":safety-concern/flag|:disposition/propose) "
       ":stake(:coordination/safety-concern か nil) :confidence(0..1)。\n"
       "重要: 未検証または未登録の端末に対する作業を提案してはいけません。"
       "シュレッダー・デガウサー等の破壊機器の直接操作(actuate)を絶対に提案してはいけません"
       "(この actor は提案のみを行い、実行は一切行いません)。"
       "R2v3適合証明・データ破壊証明を自己発行する提案をしてはいけません。"
       "データ消去レベルを偽って報告してはいけません。"))

(defn- facts-for [st {:keys [op subject value]}]
  (case op
    :log-device-intake       {:device (store/device st subject)}
    :schedule-data-wipe        {:device (store/device st (:device-id value))}
    :flag-safety-concern        {:device (and (:device-id value)
                                               (store/device st (:device-id value)))}
    :propose-disposition        {:device (store/device st (:device-id value))}
    {}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so `itad.governor`
  escalates/holds -- an LLM hiccup can never auto-schedule a wipe,
  auto-flag a concern, or auto-propose a disposition."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :itad-advisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
