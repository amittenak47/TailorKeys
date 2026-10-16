import SwiftUI

@main
struct TailorKeysApp: App {
    init() {
        // iOS 15 has no scrollContentBackground modifier.
        UITableView.appearance().backgroundColor = .clear
        let bar = UITabBarAppearance()
        bar.configureWithOpaqueBackground()
        bar.backgroundColor = .systemBackground
        UITabBar.appearance().standardAppearance = bar
        UITabBar.appearance().scrollEdgeAppearance = bar
    }

    var body: some Scene {
        WindowGroup { RootView().tint(AppStyle.accent).accentColor(AppStyle.accent) }
    }
}

struct RootView: View {
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("onboarded") private var onboarded = false
    @StateObject private var model = AppSettingsModel()
    @State private var selection = SettingsTab.layout

    var body: some View {
        TabView(selection: $selection) {
            ForEach(SettingsTab.allCases) { tab in
                SettingsScreen(model: model, tab: tab)
                    .tabItem { Label(tab.title, systemImage: tab.symbol) }
                    .tag(tab)
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
            case .importLayout: ImportLayoutSheet(model: model)
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
        .onChange(of: scenePhase) { phase in
            if phase == .active { model.refresh() }
        }
        .onChange(of: selection) { _ in model.editing = false }
    }
}
