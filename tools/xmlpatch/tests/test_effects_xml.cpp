// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#include <gtest/gtest.h>
#include <tinyxml2.h>

#include <string>
#include <string_view>
#include <vector>

#include "effects_xml.h"
#include "soundpower/protocol.h"

namespace sp::xmlpatch {
namespace {

struct Fixture {
    const char* name;
    const char* text;
};
constexpr Fixture kFixtures[] = {
#include "fixtures.inc"
};

std::string fixture(std::string_view name) {
    for (const Fixture& f : kFixtures) {
        if (name == f.name) return f.text;
    }
    ADD_FAILURE() << "missing fixture " << name;
    return {};
}

/// The fixtures that are valid effects configurations.
const std::vector<std::string> kConfigs = {"qcom_style.xml", "samsung_style.xml", "minimal.xml",
                                           "empty_root.xml", "previous_install.xml"};

std::string patched(const std::string& name, const PatchOptions& options = {}) {
    const Result r = patch(fixture(name), options);
    EXPECT_EQ(r.error, Error::None) << name << ": " << r.message;
    return r.xml;
}

std::string removed(const std::string& xml) {
    const Result r = remove(xml);
    EXPECT_EQ(r.error, Error::None) << r.message;
    return r.xml;
}

/// The effects applied in `<section><group attr=value>`, in document order. Several matching
/// groups are concatenated.
std::vector<std::string> applies(const std::string& xml, const char* section, const char* group,
                                 const char* attr, std::string_view value) {
    tinyxml2::XMLDocument doc;
    EXPECT_EQ(doc.Parse(xml.c_str()), tinyxml2::XML_SUCCESS);
    std::vector<std::string> out;
    const tinyxml2::XMLElement* root = doc.RootElement();
    if (root == nullptr) return out;
    for (auto* s = root->FirstChildElement(section); s; s = s->NextSiblingElement(section)) {
        for (auto* g = s->FirstChildElement(group); g; g = g->NextSiblingElement(group)) {
            const char* v = g->Attribute(attr);
            if (v == nullptr || value != v) continue;
            for (auto* a = g->FirstChildElement("apply"); a; a = a->NextSiblingElement("apply")) {
                out.emplace_back(a->Attribute("effect") ? a->Attribute("effect") : "");
            }
        }
    }
    return out;
}

std::vector<std::string> postprocess(const std::string& xml, std::string_view stream) {
    return applies(xml, "postprocess", "stream", "type", stream);
}

int count(const std::string& haystack, std::string_view needle) {
    int n = 0;
    for (std::size_t at = haystack.find(needle); at != std::string::npos;
         at = haystack.find(needle, at + needle.size())) {
        ++n;
    }
    return n;
}

/// The names of the root's child elements, in order.
std::vector<std::string> sections(const std::string& xml) {
    tinyxml2::XMLDocument doc;
    EXPECT_EQ(doc.Parse(xml.c_str()), tinyxml2::XML_SUCCESS);
    std::vector<std::string> out;
    for (auto* e = doc.RootElement()->FirstChildElement(); e; e = e->NextSiblingElement()) {
        out.emplace_back(e->Name());
    }
    return out;
}

using Strings = std::vector<std::string>;

TEST(XmlPatch, OutputMatchesTheReviewedExpectations) {
    for (const std::string& name : kConfigs) {
        SCOPED_TRACE(name);
        const std::string expected = name.substr(0, name.size() - 4) + ".expected.xml";
        EXPECT_EQ(patched(name), fixture(expected));
    }
}

TEST(XmlPatch, PatchingIsIdempotent) {
    for (const std::string& name : kConfigs) {
        SCOPED_TRACE(name);
        const std::string once = patched(name);
        const Result twice = patch(once, {});
        ASSERT_EQ(twice.error, Error::None);
        EXPECT_EQ(twice.xml, once);
    }
}

TEST(XmlPatch, RemoveUndoesPatch) {
    for (const std::string& name : kConfigs) {
        SCOPED_TRACE(name);
        EXPECT_EQ(removed(patched(name)), removed(fixture(name)));
        EXPECT_EQ(count(removed(patched(name)), "soundpower"), 0);
    }
}

TEST(XmlPatch, DeclaresTheLibraryAndTheEffect) {
    for (const std::string& name : kConfigs) {
        SCOPED_TRACE(name);
        const std::string xml = patched(name);
        const Summary s = inspect(xml);
        ASSERT_EQ(s.error, Error::None);
        EXPECT_EQ(s.version, "2.0");
        EXPECT_TRUE(s.library);
        EXPECT_TRUE(s.effect);
        EXPECT_EQ(s.postprocess, Strings{"music"});
        EXPECT_EQ(count(xml, "<library name=\"soundpower\" path=\"libsoundpower.so\"/>"), 1);
        EXPECT_EQ(count(xml, std::string("<effect name=\"soundpower\" library=\"soundpower\" uuid=\"") +
                                 SP_EFFECT_IMPL_UUID_STR + "\"/>"),
                  1);
        EXPECT_EQ(count(xml, "<apply effect=\"soundpower\"/>"), 1);
    }
}

TEST(XmlPatch, ReusesTheExistingMusicStream) {
    const std::string xml = patched("qcom_style.xml");
    EXPECT_EQ(postprocess(xml, "music"), (Strings{"music_helper", "soundpower"}));
    EXPECT_EQ(count(xml, "<stream type=\"music\">"), 1);
    EXPECT_EQ(count(xml, "<postprocess>"), 1);
}

TEST(XmlPatch, CreatesMissingSectionsInSchemaOrder) {
    EXPECT_EQ(sections(patched("empty_root.xml")), (Strings{"libraries", "effects", "postprocess"}));
    EXPECT_EQ(sections(patched("minimal.xml")), (Strings{"libraries", "effects", "postprocess"}));
}

TEST(XmlPatch, LeavesEverythingElseAlone) {
    const std::string before = fixture("samsung_style.xml");
    const std::string xml = patched("samsung_style.xml");
    // Comments, other streams and the preprocess section survive.
    EXPECT_NE(xml.find("<!-- Hardware implementation library -->"), std::string::npos);
    EXPECT_EQ(applies(xml, "preprocess", "stream", "type", "vendor_voice_communication"), Strings{"aec"});
    EXPECT_EQ(postprocess(xml, "ring"), Strings{"spatial3d"});
    EXPECT_EQ(postprocess(xml, "alarm"), Strings{"spatial3d"});
    EXPECT_EQ(postprocess(xml, "music"), (Strings{"spatial3d", "soundpower"}));
    EXPECT_EQ(count(xml, "<library "), count(before, "<library ") + 1);
    EXPECT_EQ(count(xml, "uuid="), count(before, "uuid=") + 1);
    // The header comment and the XML declaration survive too.
    const std::string q = patched("qcom_style.xml");
    EXPECT_EQ(q.rfind("<?xml version=\"1.0\" encoding=\"UTF-8\"?>", 0), 0u);
    EXPECT_NE(q.find("Synthetic test fixture for sp-xmlpatch"), std::string::npos);
}

TEST(XmlPatch, ReplacesTheEntriesOfAPreviousVersion) {
    const std::string xml = patched("previous_install.xml");
    EXPECT_EQ(xml.find("libsoundpower_old.so"), std::string::npos);
    EXPECT_EQ(count(xml, "name=\"soundpower\""), 2);  // one library, one effect
    EXPECT_EQ(postprocess(xml, "music"), (Strings{"equalizer", "soundpower"}));
    // The alarm stream and the device effect only held SoundPower, so they are gone.
    EXPECT_EQ(xml.find("<stream type=\"alarm\">"), std::string::npos);
    EXPECT_EQ(xml.find("deviceEffects"), std::string::npos);
    EXPECT_NE(xml.find(SP_EFFECT_IMPL_UUID_STR), std::string::npos);
}

TEST(XmlPatch, WithoutStreamsOnlyDeclares) {
    PatchOptions declareOnly;
    declareOnly.streams.clear();
    const std::string q = patched("qcom_style.xml", declareOnly);
    EXPECT_EQ(postprocess(q, "music"), Strings{"music_helper"});
    const Summary s = inspect(q);
    EXPECT_TRUE(s.library);
    EXPECT_TRUE(s.effect);
    EXPECT_TRUE(s.postprocess.empty());
    // Postprocess entries of an earlier install are removed.
    const std::string p = patched("previous_install.xml", declareOnly);
    EXPECT_EQ(count(p, "<apply effect=\"soundpower\"/>"), 0);
    EXPECT_EQ(sections(patched("empty_root.xml", declareOnly)), (Strings{"libraries", "effects"}));
}

TEST(XmlPatch, SeveralStreamsEachGetTheEffectOnce) {
    PatchOptions options;
    options.streams = {"music", "ring", "music"};
    const std::string xml = patched("samsung_style.xml", options);
    EXPECT_EQ(postprocess(xml, "music"), (Strings{"spatial3d", "soundpower"}));
    EXPECT_EQ(postprocess(xml, "ring"), (Strings{"spatial3d", "soundpower"}));
    EXPECT_EQ(postprocess(xml, "alarm"), Strings{"spatial3d"});
    EXPECT_EQ(inspect(xml).postprocess, (Strings{"music", "ring"}));
}

TEST(XmlPatch, RejectsOtherDocuments) {
    EXPECT_EQ(patch(fixture("not_effects.xml"), {}).error, Error::NotEffectsConfig);
    EXPECT_EQ(remove(fixture("not_effects.xml")).error, Error::NotEffectsConfig);
    EXPECT_EQ(inspect(fixture("not_effects.xml")).error, Error::NotEffectsConfig);
    const Result bad = patch(fixture("malformed.xml"), {});
    EXPECT_EQ(bad.error, Error::Parse);
    EXPECT_FALSE(bad.message.empty());
    EXPECT_TRUE(bad.xml.empty());
    EXPECT_EQ(inspect("").error, Error::Parse);
    EXPECT_EQ(patch("audio_effects.conf contents { }", {}).error, Error::Parse);
}

TEST(XmlPatch, ValidatesStreamTypes) {
    EXPECT_TRUE(isValidStreamType("music"));
    EXPECT_TRUE(isValidStreamType("voice_call"));
    EXPECT_TRUE(isValidStreamType("vendor_type_2"));
    for (const char* bad : {"", "Music", "two words", "a\"b", "../music", "music>"}) {
        EXPECT_FALSE(isValidStreamType(bad)) << bad;
        PatchOptions o;
        o.streams = {bad};
        EXPECT_EQ(patch(fixture("minimal.xml"), o).error, Error::BadStreamType) << bad;
    }
    EXPECT_FALSE(isValidStreamType(std::string(65, 'a')));
}

TEST(XmlPatch, InspectsAnUnpatchedFile) {
    const Summary s = inspect(fixture("qcom_style.xml"));
    ASSERT_EQ(s.error, Error::None);
    EXPECT_FALSE(s.library);
    EXPECT_FALSE(s.effect);
    EXPECT_TRUE(s.postprocess.empty());
    const Summary old = inspect(fixture("previous_install.xml"));
    EXPECT_TRUE(old.library);
    EXPECT_EQ(old.postprocess, (Strings{"music", "alarm"}));
}

}  // namespace
}  // namespace sp::xmlpatch
