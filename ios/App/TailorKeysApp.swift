import SwiftUI

// MARK: - All text in the app lives here

/// Edit every word the container app shows in this one place. Slide wording is placeholder text.
enum AppText {
    struct Slide {
        let title: String
        /// One paragraph per entry.
        let lines: [String]
        /// An SF Symbol shown in the illustration area.
        let symbol: String
    }

    static let slides: [Slide] = [
        Slide(title: "Slide 1 title", lines: ["Slide 1 text. Write this."], symbol: "keyboard"),
        Slide(title: "Slide 2 title", lines: ["Slide 2 text. Write this."], symbol: "gearshape"),
        Slide(title: "Slide 3 title", lines: ["Slide 3 text. Write this."], symbol: "globe"),
        Slide(title: "Slide 4 title", lines: ["Slide 4 text. Write this."], symbol: "square.grid.3x3"),
        Slide(title: "Slide 5 title", lines: ["Slide 5 text. Write this."], symbol: "hand.point.up.left"),
        Slide(title: "Slide 6 title", lines: ["Slide 6 text. Write this."], symbol: "pencil.line"),
    ]

    /// Index of the slide that shows the "open Settings" button.
    static let settingsButtonSlide = 1

    static let guideTab = "Guide"
    static let tryTab = "Try it"
    static let openSettingsButton = "Open Settings"
    static let backButton = "Back"
    static let nextButton = "Next"
    static let finishButton = "Finish"
    static let tryField = "Type here"
}

// MARK: - App

@main
struct TailorKeysApp: App {
    var body: some Scene {
        WindowGroup { RootView() }
    }
}

/// Two bottom tabs: the guide, and a text field to try the keyboard. All keyboard settings live inside
/// the keyboard itself. After the guide has been seen once, the app opens on "Try it".
struct RootView: View {
    @AppStorage("onboarded") private var onboarded = false
    @State private var tab = UserDefaults.standard.bool(forKey: "onboarded") ? 1 : 0

    var body: some View {
        TabView(selection: $tab) {
            GuideView(onSeenLast: { onboarded = true }, onFinish: { tab = 1 })
                .tabItem { Label(AppText.guideTab, systemImage: "book") }
                .tag(0)
            TryView()
                .tabItem { Label(AppText.tryTab, systemImage: "keyboard") }
                .tag(1)
        }
    }
}

// MARK: - Guide

struct GuideView: View {
    let onSeenLast: () -> Void
    let onFinish: () -> Void
    @State private var page = 0

    private var slides: [AppText.Slide] { AppText.slides }
    private var isLast: Bool { page >= slides.count - 1 }

    var body: some View {
        VStack(spacing: 0) {
            TabView(selection: $page) {
                ForEach(slides.indices, id: \.self) { i in
                    SlideView(slide: slides[i], showsSettingsButton: i == AppText.settingsButtonSlide)
                        .tag(i)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))

            HStack(spacing: 8) {
                ForEach(slides.indices, id: \.self) { i in
                    Capsule()
                        .fill(i == page ? Color.accentColor : Color(.tertiaryLabel))
                        .frame(width: i == page ? 22 : 8, height: 8)
                }
            }
            .padding(.vertical, 14)
            .animation(.easeInOut(duration: 0.2), value: page)

            HStack(spacing: 12) {
                Button { withAnimation { page -= 1 } } label: {
                    Text(AppText.backButton)
                        .fontWeight(.semibold)
                        .frame(maxWidth: .infinity, minHeight: 50)
                        .background(RoundedRectangle(cornerRadius: 14).fill(Color(.secondarySystemFill)))
                }
                .opacity(page == 0 ? 0 : 1)
                .disabled(page == 0)

                Button {
                    if isLast { onFinish() } else { withAnimation { page += 1 } }
                } label: {
                    Text(isLast ? AppText.finishButton : AppText.nextButton)
                        .fontWeight(.semibold)
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity, minHeight: 50)
                        .background(RoundedRectangle(cornerRadius: 14).fill(Color.accentColor))
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 16)
        }
        .onChange(of: page) { if $0 >= slides.count - 1 { onSeenLast() } }
    }
}

struct SlideView: View {
    let slide: AppText.Slide
    let showsSettingsButton: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 24).fill(Color(.secondarySystemBackground))
                    Image(systemName: slide.symbol)
                        .font(.system(size: 72, weight: .regular))
                        .foregroundColor(.accentColor)
                }
                .frame(height: 220)
                .padding(.bottom, 14)

                Text(slide.title).font(.largeTitle).fontWeight(.bold)
                ForEach(slide.lines, id: \.self) { line in
                    Text(line).font(.body).foregroundColor(.secondary)
                }
                if showsSettingsButton {
                    Button(AppText.openSettingsButton) {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                    .buttonStyle(.borderedProminent)
                    .padding(.top, 6)
                }
            }
            .padding(24)
            .frame(maxWidth: 560, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
    }
}

// MARK: - Try it

struct TryView: View {
    @State private var text = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(AppText.tryTab).font(.largeTitle).fontWeight(.bold)
            TextField(AppText.tryField, text: $text)
                .textFieldStyle(.roundedBorder)
            Spacer()
        }
        .padding(24)
        .frame(maxWidth: 560)
        .frame(maxWidth: .infinity)
    }
}
