import DebunkaxCore
import SwiftUI

struct SettingsView: View {
    @Bindable var model: SettingsViewModel
    let onOpenTrustedSources: () -> Void
    let onOpenPrivacy: () -> Void
    let onOpenSpeakerTest: () -> Void

    private static let presets: [Int?] = [nil, 20, 50, 100]

    @State private var showCustomDialog = false
    @State private var customValue = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                providerSection
                checkLimitSection

                VStack(alignment: .leading, spacing: 8) {
                    Button("Sources de confiance", action: onOpenTrustedSources)
                    Button("Confidentialité", action: onOpenPrivacy)
                }

                #if DEBUG
                VStack(alignment: .leading, spacing: 8) {
                    Toggle("Mode démo", isOn: $model.demoModeEnabled)
                        .onChange(of: model.demoModeEnabled) { _, enabled in model.demoModeChanged(enabled) }
                    Toggle("Démo audio réelle", isOn: $model.realAudioDemoModeEnabled)
                        .onChange(of: model.realAudioDemoModeEnabled) { _, enabled in
                            model.realAudioDemoModeChanged(enabled)
                        }
                    Button("Qui parle ? (test)", action: onOpenSpeakerTest)
                }
                #endif
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
        }
        .navigationTitle("Réglages")
        .navigationBarTitleDisplayMode(.inline)
        .snackbar($model.message)
        .onAppear { model.startObserving() }
        .onDisappear { model.stopObserving() }
        .alert("Limite personnalisée", isPresented: $showCustomDialog) {
            TextField("", text: $customValue).keyboardType(.numberPad)
            Button("Annuler", role: .cancel) {}
            Button("Valider") {
                if let value = Int(customValue) { model.setCheckLimit(value) }
            }
        }
        .onChange(of: customValue) { _, newValue in
            let digits = newValue.filter(\.isNumber)
            if digits != newValue { customValue = digits }
        }
    }

    // MARK: - Provider

    private var providerSection: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Fournisseur").font(.headline)

            VStack(alignment: .leading, spacing: 8) {
                Text("OpenRouter").font(.subheadline.weight(.semibold))
                Text(model.openRouterKeyStored ? "Connecté à OpenRouter." : "Non connecté.")
                Button("Se connecter avec OpenRouter", action: model.connectOpenRouter)
                    .buttonStyle(.borderedProminent)
                    .disabled(model.signingIn)
                if model.openRouterKeyStored {
                    Button("Se déconnecter", action: model.disconnectOpenRouter)
                        .buttonStyle(.bordered)
                }
                modelPicker
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(Palette.surfaceVariant, in: RoundedRectangle(cornerRadius: 12))
        }
    }

    private var modelPicker: some View {
        let models = model.openRouterModels()
        let selectedId = model.settings.provider == .openRouter ? model.settings.modelId : nil
        let selectedLabel = models.first { $0.id == selectedId }?.label ?? "Choisir un modèle"
        return VStack(alignment: .leading, spacing: 4) {
            Text("Modèle OpenRouter").font(.caption).foregroundStyle(.secondary)
            Menu {
                ForEach(models, id: \.id) { entry in
                    Button(entry.label) { model.selectModel(entry) }
                }
            } label: {
                HStack {
                    Text(selectedLabel)
                    Spacer()
                    Image(systemName: "chevron.up.chevron.down").font(.caption)
                }
                .padding(10)
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color(.separator)))
            }
        }
    }

    // MARK: - Check limit

    private var checkLimitSection: some View {
        let limit = model.settings.checkLimit
        let presetValues = Self.presets.compactMap { $0 }
        return VStack(alignment: .leading, spacing: 8) {
            Text("Limite de vérifications par session").font(.headline)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Self.presets, id: \.self) { preset in
                        limitChip(preset.map(String.init) ?? "Illimité", selected: limit == preset) {
                            model.setCheckLimit(preset)
                        }
                    }
                    limitChip("Personnalisé", selected: limit != nil && !presetValues.contains(limit ?? -1)) {
                        customValue = limit.map(String.init) ?? ""
                        showCustomDialog = true
                    }
                }
            }
        }
    }

    private func limitChip(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if selected { Image(systemName: "checkmark").font(.caption) }
                Text(label).font(.subheadline)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(selected ? Palette.primary.opacity(0.2) : Color.clear, in: Capsule())
            .overlay(Capsule().stroke(Color(.separator)))
        }
        .buttonStyle(.plain)
    }
}
