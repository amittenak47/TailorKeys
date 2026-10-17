import SwiftUI

/// Uses explicit height reporting because UIViewRepresentable's sizing hook requires iOS 16.
struct KeyboardPreview: View {
    @ObservedObject var model: AppSettingsModel
    let landscape: Bool?
    let permitsEditing: Bool
    @State private var height: CGFloat = 260

    var body: some View {
        GeometryReader { proxy in
            KeyboardPreviewView(model: model, landscape: landscape, permitsEditing: permitsEditing,
                                width: proxy.size.width, height: $height)
        }
        .frame(height: height)
        .clipShape(RoundedRectangle(cornerRadius: 24))
        .accessibilityLabel("Keyboard preview")
    }
}

private struct KeyboardPreviewView: UIViewRepresentable {
    @ObservedObject var model: AppSettingsModel
    let landscape: Bool?
    let permitsEditing: Bool
    let width: CGFloat
    @Binding var height: CGFloat

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> KeyboardView {
        let prefs = KeyboardSettings(tablet: UIDevice.current.userInterfaceIdiom == .pad, store: model.group)
        prefs.previewOrientation = landscape
        context.coordinator.settings = prefs
        let view = KeyboardView(settings: prefs, grid: model.grid)
        view.delegate = context.coordinator
        context.coordinator.keyboard = view
        view.setSuggestions(left: ["helpful", "helicopter", "helmet"], right: ["hello", "help", "held"], prefix: "hel")
        view.onHeightChange = { [weak coordinator = context.coordinator] height in
            coordinator?.reportHeight(height)
        }
        // Observe the preview toolbar's own Done button without consuming keyboard touches.
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.toolbarTapped))
        tap.cancelsTouchesInView = false
        tap.delaysTouchesEnded = false
        tap.delegate = context.coordinator
        view.addGestureRecognizer(tap)
        return view
    }

    func updateUIView(_ view: KeyboardView, context: Context) {
        context.coordinator.parent = self
        context.coordinator.settings?.previewOrientation = landscape
        if view.grid !== model.grid { view.grid = model.grid }
        view.editMode = permitsEditing && model.editing && model.fullAccessConfirmed
        // Normal keyboard gestures can change width/alignment internally. Only editing accepts touches here.
        view.isUserInteractionEnabled = view.editMode
        context.coordinator.configureScrolling(editing: view.editMode)
        view.reloadSettings()
        context.coordinator.reportHeight(view.height(forWidth: max(1, width)))
    }

    static func dismantleUIView(_ view: KeyboardView, coordinator: Coordinator) {
        coordinator.configureScrolling(editing: false)
        view.editMode = false
        view.cancelTouches()
        view.onHeightChange = nil
        view.delegate = nil
    }

    final class Coordinator: NSObject, KeyboardViewDelegate, UIGestureRecognizerDelegate {
        var parent: KeyboardPreviewView
        var settings: KeyboardSettings?
        weak var keyboard: KeyboardView?
        private var pendingHeight: CGFloat?
        private weak var scroll: UIScrollView?
        private var scrollBehavior: (cancel: Bool, delay: Bool)?

        init(_ parent: KeyboardPreviewView) { self.parent = parent }

        func reportHeight(_ value: CGFloat) {
            guard abs(parent.height - value) > 0.5, pendingHeight != value else { return }
            pendingHeight = value
            DispatchQueue.main.async { [weak self] in
                guard let self = self, let height = self.pendingHeight else { return }
                self.pendingHeight = nil
                if abs(self.parent.height - height) > 0.5 { self.parent.height = height }
            }
        }

        func configureScrolling(editing: Bool) {
            if !editing {
                if let scroll = scroll, let behavior = scrollBehavior {
                    scroll.canCancelContentTouches = behavior.cancel
                    scroll.delaysContentTouches = behavior.delay
                }
                scroll = nil
                scrollBehavior = nil
                return
            }
            // A large preview sits inside the list. Keep its key drags from becoming list scrolls.
            var ancestor = keyboard?.superview
            while let view = ancestor {
                if let list = view as? UIScrollView {
                    if scroll !== list {
                        configureScrolling(editing: false)
                        scroll = list
                        scrollBehavior = (list.canCancelContentTouches, list.delaysContentTouches)
                    }
                    list.canCancelContentTouches = false
                    list.delaysContentTouches = false
                    break
                }
                ancestor = view.superview
            }
        }

        @objc func toolbarTapped() {
            DispatchQueue.main.async { [weak self] in
                guard let self = self, let keyboard = self.keyboard else { return }
                if self.parent.model.editing && !keyboard.editMode { self.parent.model.editing = false }
            }
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool { true }

        func gridEdited() {
            if let keyboard = keyboard { parent.model.saveGrid(keyboard.grid) }
        }
        func keyTapped(_ code: String) {}
        func longPressText(_ text: String) {}
        func stackFace(_ code: String) {}
        func suggestionPicked(_ text: String) {}
        func switchKeyboard() {}
        func openSettings() {}
        func moveCursor(_ steps: Int) {}
    }
}
