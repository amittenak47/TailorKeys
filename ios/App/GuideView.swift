import SwiftUI

// MARK: - Guide text

/// Every user-visible string of the guide, in one place.
enum AppText {
    struct Slide {
        let title: String
        /// One paragraph per entry.
        let lines: [String]
        /// An SF Symbol shown in the illustration area.
        let symbol: String
    }

    static let slides: [Slide] = [
        Slide(title: "A keyboard that fits you", lines: [
            "Move any key anywhere. Make it bigger, smaller, or split it for two thumbs.",
            "Suggestions wait above the keys. Nothing you type changes unless you pick a word.",
        ], symbol: "keyboard"),
        Slide(title: "Add TailorKeys", lines: [
            "Open Settings, tap Keyboards, and turn on TailorKeys.",
            "Full Access is optional. TailorKeys has no network code, so your typing stays on this device.",
        ], symbol: "gearshape"),
        Slide(title: "Switch to TailorKeys", lines: [
            "In any text field, hold the globe key and choose TailorKeys.",
            "To switch back later, tap the globe.",
        ], symbol: "globe"),
        Slide(title: "Put keys where you want them", lines: [
            "Tap the gear above the keys, then Rearrange. Drag a key to move it.",
            "Drop a key on another key's edge to slide it in. Drop it in the middle to stack both into one swipe key.",
        ], symbol: "square.grid.3x3"),
        Slide(title: "Swipe keys and suggestions", lines: [
            "Swipe a stacked key up or down to change it. Tap abc once for a capital, twice for caps lock.",
            "Spin a suggestion wheel sideways and tap a word, or hold a spacebar to pick that side's word.",
            "To move the cursor, hold the empty space between the keys and push.",
        ], symbol: "hand.point.up.left"),
        Slide(title: "Try it", lines: [
            "Type a few words below. Size, split and tilt are in this app.",
        ], symbol: "pencil.line"),
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
                    SlideView(slide: slides[i], showsSettingsButton: i == AppText.settingsButtonSlide, showsPractice: i == slides.count - 1)
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
    let showsPractice: Bool
    @State private var practice = ""
    @FocusState private var practiceFocused: Bool

    var body: some View {
        ScrollViewReader { reader in
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
                    if showsPractice {
                        TextField(AppText.tryField, text: $practice)
                            .textFieldStyle(.roundedBorder)
                            .focused($practiceFocused)
                            .id("practice")
                            .padding(.vertical, 8)
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
            .onChange(of: practiceFocused) { focused in
                if focused { withAnimation { reader.scrollTo("practice", anchor: .bottom) } }
            }
        }
    }
}

