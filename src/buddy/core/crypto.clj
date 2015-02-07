;; Copyright (c) 2013-2015 Andrey Antukh <niwi@niwi.be>
;;
;; Licensed under the Apache License, Version 2.0 (the "License")
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns buddy.core.crypto
  "Modes implementation"
  (:import org.bouncycastle.crypto.engines.TwofishEngine
           org.bouncycastle.crypto.engines.BlowfishEngine
           org.bouncycastle.crypto.engines.AESEngine
           org.bouncycastle.crypto.engines.ChaChaEngine
           org.bouncycastle.crypto.modes.CBCBlockCipher
           org.bouncycastle.crypto.modes.SICBlockCipher
           org.bouncycastle.crypto.modes.OFBBlockCipher
           org.bouncycastle.crypto.params.ParametersWithIV
           org.bouncycastle.crypto.params.KeyParameter
           clojure.lang.IFn
           clojure.lang.Keyword))


(def ^{:doc "Supported block cipher modes."
       :dynamic true}
  *supported-modes* {:ecb #(identity %)
                     :cbc #(CBCBlockCipher. %)
                     :ctr #(SICBlockCipher. %)
                     :sic #(SICBlockCipher. %)
                     :ofb #(OFBBlockCipher. %1 (* 8 (.getBlockSize %1)))})

(def ^{:doc "Supported block ciphers."
       :dynamic true}
  *supported-block-ciphers* {:twofish #(TwofishEngine.)
                             :blowfish #(BlowfishEngine.)
                             :aes #(AESEngine.)})

(def ^{:doc "Supported block ciphers."
       :dynamic true}
  *supported-stream-ciphers* {:chacha #(ChaChaEngine.)})


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Private api: type declarations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol Cipher
  (initialize! [obj params] "Initialize cipher"))

(defprotocol BlockCipher
  "Protocol that defines interface for all
  supported block ciphers by `buddy`."
  (process-block! [obj input] "Encrypt/Decrypt a block of bytes."))

(defprotocol StreamCipher
  "Protocol that defines interface for all
  supported stream ciphers by `buddy`."
  (process-bytes! [obj input] "Encrypt/Decrypt a set of bytes."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Private api: internal implementation.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- make-block-cipher-params
  "Generate CipherParameters instance from
  clojure hash map."
  [params]
  (if (:iv params)
    (let [keyparams (make-block-cipher-params (dissoc params :iv))]
      (ParametersWithIV. keyparams (:iv params)))
    (KeyParameter. (:key params))))

(defn- initialize-cipher!
  [engine params]
  (let [encrypt (case (:op params)
                  :encrypt true
                  :decrypt false
                  true)
        cipher-params (make-block-cipher-params (dissoc params :op))]
    (.init engine encrypt cipher-params)))

(defn- process-block-with-block-cipher!
  [engine input]
  (let [buffer (byte-array (.getBlockSize engine))]
    (.processBlock engine input 0 buffer 0)
    buffer))

(defn- process-bytes-with-stream-cipher!
  [engine input]
  (let [len    (count input)
        buffer (byte-array len)]
    (.processBytes engine input 0 len buffer 0)
    buffer))

(extend-type CBCBlockCipher
  Cipher
  (initialize! [engine params]
    (initialize-cipher! engine params))

  BlockCipher
  (process-block! [engine input]
    (process-block-with-block-cipher! engine input)))

(extend-type SICBlockCipher
  Cipher
  (initialize! [engine params]
    (initialize-cipher! engine params))

  BlockCipher
  (process-block! [engine input]
    (process-block-with-block-cipher! engine input)))

(extend-type OFBBlockCipher
  Cipher
  (initialize! [engine params]
    (initialize-cipher! engine params))

  BlockCipher
  (process-block! [engine input]
    (process-block-with-block-cipher! engine input)))

(extend-type ChaChaEngine
  Cipher
  (initialize! [engine params]
    (initialize-cipher! engine params))

  StreamCipher
  (process-bytes! [engine input]
    (process-bytes-with-stream-cipher! engine input)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api: Low level
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- cipher-supported?
  [^Keyword cipher]
  (contains? *supported-block-ciphers* cipher))

(defn- stream-cipher-supported?
  [^Keyword cipher]
  (contains? *supported-stream-ciphers* cipher))

(defn- ciphermode-supported?
  [^Keyword mode]
  (contains? *supported-modes* mode))

(defn engine
  "Block cipher engine constructor."
  [^Keyword cipher ^Keyword mode]
  {:pre [(cipher-supported? cipher)
         (ciphermode-supported? mode)]}
  (let [modefactory   (mode *supported-modes*)
        enginefactory (cipher *supported-block-ciphers*)]
    (modefactory (enginefactory))))

(defn stream-engine
  "Stream cipher engine constructor."
  [^Keyword cipher]
  {:pre [(stream-cipher-supported? cipher)]}
  (let [enginefactory (cipher *supported-stream-ciphers*)]
    (enginefactory)))
