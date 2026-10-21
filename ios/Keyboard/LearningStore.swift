import Foundation

enum LearningStore {
    static let revisionKey = "learning_revision"
    static let queue = DispatchQueue(label: "app.tailorkeys.learning", qos: .utility)
    static func suffix(_ language: String) -> String { language == "en" ? "" : "_" + language }
    static func data(_ store: UserDefaults, _ language: String) -> [String: Any] {
        let ending = suffix(language)
        if let bundle = store.dictionary(forKey: "user_bundle" + ending) {
            // A snapshot from the extension cannot resurrect words forgotten in the app.
            guard bundle["revision"] as? String == (store.string(forKey: revisionKey) ?? "") else { return [:] }
            return bundle
        }
        var legacy: [String: Any] = [:]
        for kind in ["words", "bigrams", "trigrams", "seen", "used", "decay"] {
            legacy[kind] = store.object(forKey: "user_" + kind + ending)
        }
        return legacy
    }
    static func words(_ store: UserDefaults) -> [(language: String, word: String)] {
        Prefs.languageCodes.flatMap { language in
            (data(store, language)["words"] as? [String: Int] ?? [:]).keys.map { (language: language, word: $0) }
        }.sorted { $0.word == $1.word ? $0.language < $1.language : $0.word < $1.word }
    }
    static func forget(_ store: UserDefaults, language: String? = nil, word: String? = nil) {
        queue.sync {
            let all = Dictionary(uniqueKeysWithValues: Prefs.languageCodes.map { ($0, data(store, $0)) })
            let revision = UUID().uuidString
            store.set(revision, forKey: revisionKey)
            for lang in Prefs.languageCodes {
                var bundle = all[lang] ?? [:]
                if language == nil || language == lang {
                    if let word = word?.lowercased() {
                        for kind in ["bigrams", "trigrams"] {
                            let rows = bundle[kind] as? [String: [String: Int]] ?? [:]
                            bundle[kind] = rows.filter { !$0.key.components(separatedBy: "\t").contains(word) }.mapValues { $0.filter { $0.key != word } }
                        }
                        for kind in ["words", "seen", "used"] {
                            var values = bundle[kind] as? [String: Any] ?? [:]
                            values.removeValue(forKey: word); bundle[kind] = values
                        }
                    } else { bundle = [:] }
                }
                bundle["revision"] = revision
                store.set(bundle, forKey: "user_bundle" + suffix(lang))
                for kind in ["words", "bigrams", "trigrams", "seen", "used", "decay"] { store.removeObject(forKey: "user_" + kind + suffix(lang)) }
            }
        }
    }
}
