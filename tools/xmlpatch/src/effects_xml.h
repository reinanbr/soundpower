// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// Edits an Android audio effects configuration (audio_effects*.xml, schema
// "audio_effects_conf" 2.0) so that it declares the SoundPower library and effect, and applies the
// effect to the requested output streams. Every edit first removes whatever SoundPower added
// before, so patching is idempotent and works over a previous version's output.
#pragma once

#include <string>
#include <string_view>
#include <vector>

namespace sp::xmlpatch {

/// The names SoundPower uses inside an effects configuration.
inline constexpr const char* kLibraryName = "soundpower";
inline constexpr const char* kLibraryFile = "libsoundpower.so";  // looked up in lib*/soundfx/
inline constexpr const char* kEffectName = "soundpower";

enum class Error {
    None,
    Parse,             ///< not well-formed XML
    NotEffectsConfig,  ///< well-formed, but the root element is not <audio_effects_conf>
    BadStreamType,     ///< a requested stream type is not a plain lower-case identifier
};

struct PatchOptions {
    /// Output stream types (`<postprocess><stream type="...">`) the effect is applied to. Empty
    /// declares the library and effect only, for the mode where the app attaches the effect itself.
    std::vector<std::string> streams{"music"};
};

struct Result {
    Error error = Error::None;
    std::string message;  ///< details when error != None
    std::string xml;      ///< the rewritten document when error == None
};

struct Summary {
    Error error = Error::None;
    std::string message;
    std::string version;                   ///< the root's "version" attribute
    bool library = false;                  ///< <library name="soundpower"> is declared
    bool effect = false;                   ///< <effect name="soundpower"> is declared
    std::vector<std::string> postprocess;  ///< stream types whose postprocess applies the effect
};

/// Removes previous SoundPower entries, then adds the library, the effect and the postprocess
/// entries. Sections and streams are created when missing; existing ones are reused.
[[nodiscard]] Result patch(std::string_view xml, const PatchOptions& options);

/// Removes every SoundPower entry, and any section or stream left empty by that removal.
[[nodiscard]] Result remove(std::string_view xml);

/// Reports what SoundPower entries the document has.
[[nodiscard]] Summary inspect(std::string_view xml);

[[nodiscard]] bool isValidStreamType(std::string_view type);
[[nodiscard]] const char* toString(Error error);

}  // namespace sp::xmlpatch
