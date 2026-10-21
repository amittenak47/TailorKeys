import SwiftUI

@main
struct TailorKeysApp: App {
    init() {
        // iOS 15 has no scrollContentBackground modifier.
        UITableView.appearance().backgroundColor = .clear
        UITextView.appearance().backgroundColor = .clear
    }

    var body: some Scene {
        WindowGroup { RootView().tint(AppStyle.accent).accentColor(AppStyle.accent) }
    }
}

struct RootView: View {
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @AppStorage("onboarded") private var onboarded = false
    @StateObject private var model = AppSettingsModel()
    @State private var selection = SettingsTab.layout

    var body: some View {
        VStack(spacing: 0) {
            if selection == .layout { LayoutScreen(model: model) }
            else { SettingsScreen(model: model, tab: selection) }
            if !model.testing {
                SettingsNavigation(selection: $selection)
                    .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity).animation(.easeOut(duration: 0.22)))
            }
        }
        .sheet(item: $model.sheet) { sheet in
            switch sheet {
            case .guide:
                NavigationView {
                    GuideView(onSeenLast: { onboarded = true }, onFinish: { model.sheet = nil })
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) {
                                Button("Done") { model.sheet = nil }
                            }
                        }
                }
                .navigationViewStyle(.stack)
            case .fullAccess: FullAccessSheet()
            case .share: LayoutShareSheet(code: model.shareCode)
            case .about: AboutSheet()
            }
        }
        .onAppear {
            model.refresh()
            if !onboarded {
                onboarded = true
                model.sheet = .guide
            }
        }
        .onOpenURL { url in
            if url.scheme == "tailorkeys" && url.host == "settings" {
                model.sheet = nil
                selection = .settings
            }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .background && model.testing { model.endTest() }
            if phase == .active {
                model.refresh()
                if selection != .layout { model.previewAppliedLayout() }
            }
        }
        .onChange(of: selection) { tab in
            model.editing = false
            if model.importOpen { model.cancelImport() }
            if tab == .layout { model.previewSelectedLayout() }
            else { model.previewAppliedLayout() }
        }
    }
}
