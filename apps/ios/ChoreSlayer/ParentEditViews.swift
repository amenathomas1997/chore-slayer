import SwiftUI

struct ChildEditorView: View {
    @ObservedObject var model: AppModel
    @State private var first = ""
    @State private var last = ""
    @State private var emoji = "🙂"
    @State private var color = "#5b8def"
    @State private var confirmDelete = false
    @State private var confirmRemove = false
    @State private var didInit = false

    private let emojis = ["🙂", "😀", "🦊", "🐱", "🦄", "🐶", "🌟", "🚀", "🦖", "🐢"]
    /// Plain names, so VoiceOver, Voice Control and Dragon users can say them.
    private let emojiNames = ["🙂": "Smiling face", "😀": "Grinning face", "🦊": "Fox", "🐱": "Cat", "🦄": "Unicorn",
                              "🐶": "Dog", "🌟": "Star", "🚀": "Rocket", "🦖": "Dinosaur", "🐢": "Turtle"]
    private let colors = ["#5b8def", "#e8618c", "#33b679", "#f5a623", "#6d4fc0", "#16a3b8"]
    private let grid = [GridItem(.adaptive(minimum: 52), spacing: 8)]

    private var existing: Child? { model.editingChild }
    /// Something differs from what was loaded (or from a blank new child).
    private var dirty: Bool {
        guard didInit else { return false }
        if let c = existing {
            return first != c.firstName || last != c.lastName || emoji != c.emoji || color != c.color
        }
        return !first.trimmingCharacters(in: .whitespaces).isEmpty || !last.trimmingCharacters(in: .whitespaces).isEmpty
            || emoji != "🙂" || color != "#5b8def"
    }
    private var canSave: Bool {
        !model.busy
            && !first.trimmingCharacters(in: .whitespaces).isEmpty
            && !last.trimmingCharacters(in: .whitespaces).isEmpty
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(existing == nil ? "Add a child" : "Edit child")
                .font(.title2.bold()).screenHeading()

            LabeledTextField(title: "First name", text: $first)
            LabeledTextField(title: "Last name", text: $last)

            Text("Icon").font(.subheadline.weight(.semibold)).accessibilityAddTraits(.isHeader)
            LazyVGrid(columns: grid, spacing: 8) {
                ForEach(emojis, id: \.self) { e in
                    Button { emoji = e } label: {
                        Text(e).font(.title)
                            .frame(width: 48, height: 48)
                            .background(emoji == e ? Color.brand.opacity(0.25) : Color(.secondarySystemBackground))
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                            .overlay(RoundedRectangle(cornerRadius: 10)
                                .strokeBorder(emoji == e ? Color.brand : Color.clear, lineWidth: 3))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(emojiNames[e] ?? "Emoji") icon")
                    .accessibilityInputLabels([emojiNames[e] ?? "Emoji"])
                    .accessibilityAddTraits(emoji == e ? [.isButton, .isSelected] : .isButton)
                }
            }
            .choiceGroup("Icon")

            Text("Color").font(.subheadline.weight(.semibold)).accessibilityAddTraits(.isHeader)
            HStack(spacing: 12) {
                ForEach(colors, id: \.self) { hex in
                    Button { color = hex } label: {
                        Circle().fill(Color(hex: hex))
                            .frame(width: 44, height: 44)
                            .overlay(
                                Circle().stroke(color == hex ? Color.primary : Color.primary.opacity(0.3),
                                                lineWidth: color == hex ? 3 : 1))
                            .overlay {
                                if color == hex {
                                    Image(systemName: "checkmark")
                                        .font(.body.weight(.heavy))
                                        .foregroundStyle(.white)
                                        .shadow(color: .black.opacity(0.8), radius: 1)
                                        .accessibilityHidden(true)
                                }
                            }
                            .padding(3)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(colorName(hex))
                    .accessibilityAddTraits(color == hex ? [.isButton, .isSelected] : .isButton)
                }
            }
            .choiceGroup("Color")

            Button(existing == nil ? "Add child" : "Save changes") {
                Task {
                    await model.saveChild(
                        existing: existing,
                        firstName: first.trimmingCharacters(in: .whitespaces),
                        lastName: last.trimmingCharacters(in: .whitespaces),
                        color: color, emoji: emoji)
                }
            }
            .primaryButton()
            .frame(maxWidth: .infinity, minHeight: 48)
            .disabled(!canSave)

            if existing != nil {
                Divider()
                Button("Remove (keep their history)") { confirmRemove = true }
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity, minHeight: 44)
                Button("Delete forever", role: .destructive) { confirmDelete = true }
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .tint(.danger)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onAppear {
            if let c = existing {
                first = c.firstName; last = c.lastName; emoji = c.emoji; color = c.color
            } else {
                first = ""; last = ""; emoji = "🙂"; color = "#5b8def"
            }
            didInit = true
            model.childEditorDirty = false
        }
        // Back asks "Discard changes?" while there are unsaved edits.
        .onChange(of: dirty) { model.childEditorDirty = $0 }
        .onDisappear { model.childEditorDirty = false }
        .confirmationDialog("Remove \(existing?.firstName ?? "this child")?", isPresented: $confirmRemove, titleVisibility: .visible) {
            Button("Remove", role: .destructive) {
                Task { await model.removeManagedChild(forever: false) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("They'll be taken off the children list. Their check-offs and reports are kept.")
        }
        .alert("Delete \(existing?.firstName ?? "child") forever?", isPresented: $confirmDelete) {
            Button("Delete forever", role: .destructive) {
                Task { await model.removeManagedChild(forever: true) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This permanently erases this child and all of their tasks, check-offs, and reports.")
        }
    }
}
