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

(ns buddy.core.mac.hmac
  "Hash-based Message Authentication Codes (HMACs)"
  (:require [buddy.core.codecs :refer :all]
            [buddy.core.mac.proto :as proto]
            [buddy.core.hash :as hash]
            [clojure.java.io :as io])
  (:import org.bouncycastle.crypto.macs.HMac
           org.bouncycastle.crypto.Mac
           org.bouncycastle.crypto.params.KeyParameter
           clojure.lang.Keyword
           buddy.Arrays))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Low level hmac engine.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn hmac-engine
  "Create a hmac engine."
  [key alg]
  (let [digest (hash/resolve-digest alg)
        kp     (KeyParameter. (->byte-array key))
        mac    (HMac. digest)]
    (reify
      proto/IMac
      (update [_ input offset length]
        (.update mac input offset length))
      (end [_]
        (let [buffer (byte-array (.getMacSize mac))]
          (.doFinal mac buffer 0)
          buffer)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Low level private function with all logic for make
;; hmac for distinct types.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- make-hmac-for-plain-data
  [^bytes input key ^Keyword alg]
  (let [engine (hmac-engine key alg)]
    (proto/update! engine input)
    (proto/end! engine)))

(defn- verify-hmac-for-plain-data
  [^bytes input, ^bytes signature, pkey, ^Keyword alg]
  (let [sig (make-hmac-for-plain-data input pkey alg)]
    (Arrays/equals sig signature)))

(defn- make-hmac-for-stream
  [^java.io.InputStream input key ^Keyword alg]
  (let [engine (hmac-engine key alg)
        buffer (byte-array 5120)]
    (loop []
      (let [readed (.read input buffer 0 5120)]
        (when-not (= readed -1)
          (proto/update! engine buffer 0 readed)
          (recur))))
    (proto/end! engine)))

(defn- verify-hmac-for-stream
  [^java.io.InputStream input, ^bytes signature, pkey, ^Keyword alg]
  (let [sig (make-hmac-for-stream input pkey alg)]
    (Arrays/equals sig signature)))

(defprotocol HMacType
  "Unified protocol for calculate a keyed-hash message.
  It comes with default implementations for bytes, String,
  InputStream, File, URL and URI."
  (make-hmac [data key alg] "Calculate hmac for input using key and alg.")
  (verify-hmac [data signature key alg] "Verify hmac for input using key and alg."))

(alter-meta! #'make-hmac assoc :no-doc true :private true)
(alter-meta! #'verify-hmac assoc :no-doc true :private true)

(extend-protocol HMacType
  (Class/forName "[B")
  (make-hmac [^bytes input key ^Keyword alg]
    (make-hmac-for-plain-data input key alg))
  (verify-hmac [^bytes input ^bytes signature ^String key ^Keyword alg]
    (verify-hmac-for-plain-data input signature key alg))

  java.lang.String
  (make-hmac [^String input key ^Keyword alg]
    (make-hmac-for-plain-data (->byte-array input) key alg))
  (verify-hmac [^String input ^bytes signature ^String key ^Keyword alg]
    (verify-hmac-for-plain-data (->byte-array input) signature key alg))

  java.io.InputStream
  (make-hmac [^java.io.InputStream input key ^Keyword alg]
    (make-hmac-for-stream input key alg))
  (verify-hmac [^java.io.InputStream input ^bytes signature ^String key ^Keyword alg]
    (verify-hmac-for-stream input signature key alg))

  java.io.File
  (make-hmac [^java.io.File input key ^Keyword alg]
    (make-hmac-for-stream (io/input-stream input) key alg))
  (verify-hmac [^java.io.File input ^bytes signature ^String key ^Keyword alg]
    (verify-hmac-for-stream (io/input-stream input) signature key alg))

  java.net.URL
  (make-hmac [^java.net.URL input key ^Keyword alg]
    (make-hmac-for-stream (io/input-stream input) key alg))
  (verify-hmac [^java.net.URL input ^bytes signature ^String key ^Keyword alg]
    (verify-hmac-for-stream (io/input-stream input) signature key alg))

  java.net.URI
  (make-hmac [^java.net.URI input key ^Keyword alg]
    (make-hmac-for-stream (io/input-stream input) key alg))
  (verify-hmac [^java.net.URI input ^bytes signature ^String key ^Keyword alg]
    (verify-hmac-for-stream (io/input-stream input) signature key alg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; High level interface
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn hmac
  "Make hmac for arbitrary input.

  Example:
    (hmac \"foo bar\" \"secret\" :sha256)
    ;; => #<byte[] [B@465d154e>
  "
  [input key ^Keyword alg]
  (make-hmac input key alg))

(defn verify
  "Verify hmac for artbitrary input and signature.

  Example:
    (let [signature (hex->bytes \"61849448bdbb67b39d609471eead6...\")]
      (verify \"foo bar\" signature \"secret\" :sha256))
    ;; => true
  "
  [input ^bytes signature key ^Keyword alg]
  (verify-hmac input signature key alg))
