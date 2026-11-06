import SwiftUI

struct ChildEditorView: View {
    @ObservedObject var model: AppModel
    @State private var first = ""
    @State private var last = ""
    @State private var emoji = "🙂"
    @State private var color = "#5b8def"
    @State private var confirmDelete = false

    private let emojis = ["🙂", "😀", "🦊", "🐱", "🦄", "🐶", "🌟", "🚀", "🦖", "🐢"]
    private let colors = ["#5b8def", "#e8618c", "#33b679", "#f5a623", "#6d4fc0", "#16a3b8"]
    private let grid = [GridItem(.adaptive(minimum: 52), spacing: 8)]

    private var existing: Child? { model.editingChild }
    private var canSave: Bool {
        !model.busy
            && !first.trimmingCharacters(in: .whitespaces).isEmpty
            && !last.trimmingCharacters(in: .whitespaces).isEmpty
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(existing == nil ? "Add a child" : "Edit child")
                .font(.title2.bold()).accessibilityAddTraits(.isHeader)

            TextField("First name", text: $first).textFieldStyle(.roundedBorder)
            TextField("Last name", text: $last).textFieldStyle(.roundedBorder)

            Text("Icon").font(.subheadline.weight(.semibold)).accessibilityAddTraits(.isHeader)
            LazyVGrid(columns: grid, spacing: 8) {
                ForEach(emojis, id: \.self) { e in
                    Button { emoji = e } label: {
                        Text(e).font(.title)
                            .frame(width: 48, height: 48)
                            .background(emoji == e ? Color.brand.opacity(0.25) : Color(.secondarySystemBackground))
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Icon \(e)")
                    .accessibilityAddTraits(.isButton)
                    .accessibilityAddTraits(emoji == e ? .isSelected : .isButton)
                }
            }

            Text("Color").font(.subheadline.weight(.semibold)).accessibilityAddTraits(.isHeader)
            HStack(spacing: 12) {
                ForEach(colors, id: \.self) { hex in
                    Button { color = hex } label: {
                        Circle().fill(Color(hex: hex))
                            .frame(width: 44, height: 44)
                            .overlay(
                                Circle().stroke(color == hex ? Color.black : Color.black.opacity(0.2),
                                                lineWidth: color == hex ? 3 : 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(colorName(hex))
                    .accessibilityAddTraits(.isButton)
                    .accessibilityAddTraits(color == hex ? .isSelected : .isButton)
                }
            }

            Button(existing == nil ? "Add child" : "Save changes") {
                Task {
                    await model.saveChild(
                        existing: existing,
                        firstName: first.trimmingCharacters(in: .whitespaces),
                        lastName: last.trimmingCharacters(in: .whitespaces),
                        color: color, emoji: emoji)
                }
            }
            .buttonStyle(.borderedProminent)
            .frame(maxWidth: .infinity, minHeight: 48)
            .disabled(!canSave)

            if existing != nil {
                Divider()
                Button("Remove (keep their history)") {
                    Task { await model.removeManagedChild(forever: false) }
                }
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
