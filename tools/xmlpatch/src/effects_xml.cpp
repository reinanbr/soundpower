// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#include "effects_xml.h"

#include <tinyxml2.h>

#include <algorithm>

#include "soundpower/protocol.h"

namespace sp::xmlpatch {
namespace {

using tinyxml2::XMLDocument;
using tinyxml2::XMLElement;
using tinyxml2::XMLPrinter;

constexpr const char* kRootName = "audio_effects_conf";

bool attributeIs(const XMLElement* e, const char* name, std::string_view value) {
    const char* v = e->Attribute(name);
    return v != nullptr && value == v;
}

/// Deletes the child elements of `parent` named `tag` whose attribute `attr` equals `value`.
int removeChildren(XMLElement* parent, const char* tag, const char* attr, std::string_view value) {
    int removed = 0;
    XMLElement* e = parent->FirstChildElement(tag);
    while (e != nullptr) {
        XMLElement* next = e->NextSiblingElement(tag);
        if (attributeIs(e, attr, value)) {
            parent->DeleteChild(e);
            ++removed;
        }
        e = next;
    }
    return removed;
}

/// Calls `edit(section)` for every child element of `root` named `tag`, and deletes the section
/// when the edit removed something and left it without child elements.
template <typename Edit>
void editSections(XMLElement* root, const char* tag, Edit edit) {
    XMLElement* section = root->FirstChildElement(tag);
    while (section != nullptr) {
        XMLElement* next = section->NextSiblingElement(tag);
        if (edit(section) > 0 && section->FirstChildElement() == nullptr) root->DeleteChild(section);
        section = next;
    }
}

/// Removes `<apply effect="soundpower"/>` from every `group` (a <stream> or <devicePort>) of a
/// section, deleting groups the removal leaves empty.
int removeApplies(XMLElement* section, const char* group) {
    int removed = 0;
    editSections(section, group, [&](XMLElement* g) {
        const int n = removeChildren(g, "apply", "effect", kEffectName);
        removed += n;
        return n;
    });
    return removed;
}

void strip(XMLElement* root) {
    editSections(root, "libraries", [](XMLElement* s) {
        return removeChildren(s, "library", "name", kLibraryName);
    });
    editSections(root, "effects", [](XMLElement* s) {
        return removeChildren(s, "effect", "name", kEffectName) +
               removeChildren(s, "effectProxy", "name", kEffectName);
    });
    for (const char* section : {"preprocess", "postprocess"}) {
        editSections(root, section, [](XMLElement* s) { return removeApplies(s, "stream"); });
    }
    editSections(root, "deviceEffects", [](XMLElement* s) { return removeApplies(s, "devicePort"); });
}

XMLElement* findChild(XMLElement* parent, const char* tag, const char* attr, std::string_view value) {
    for (XMLElement* e = parent->FirstChildElement(tag); e != nullptr; e = e->NextSiblingElement(tag)) {
        if (attributeIs(e, attr, value)) return e;
    }
    return nullptr;
}

std::string print(const XMLDocument& doc) {
    XMLPrinter printer;
    doc.Print(&printer);
    return printer.CStr();
}

/// Parses `xml` and checks the root; returns the root element or nullptr with `error` set.
XMLElement* load(XMLDocument& doc, std::string_view xml, Error& error, std::string& message) {
    if (doc.Parse(xml.data(), xml.size()) != tinyxml2::XML_SUCCESS) {
        error = Error::Parse;
        message = doc.ErrorStr();
        return nullptr;
    }
    XMLElement* root = doc.RootElement();
    if (root == nullptr || std::string_view(root->Name()) != kRootName) {
        error = Error::NotEffectsConfig;
        message = std::string("root element is <") + (root != nullptr ? root->Name() : "") +
                  ">, not <" + kRootName + ">";
        return nullptr;
    }
    return root;
}

}  // namespace

bool isValidStreamType(std::string_view type) {
    return !type.empty() && type.size() <= 64 &&
           std::all_of(type.begin(), type.end(), [](char c) {
               return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
           });
}

Result patch(std::string_view xml, const PatchOptions& options) {
    Result result;
    for (const std::string& type : options.streams) {
        if (!isValidStreamType(type)) {
            result.error = Error::BadStreamType;
            result.message = "invalid stream type '" + type + "'";
            return result;
        }
    }
    XMLDocument doc;
    XMLElement* root = load(doc, xml, result.error, result.message);
    if (root == nullptr) return result;
    strip(root);

    XMLElement* libraries = root->FirstChildElement("libraries");
    if (libraries == nullptr) {
        libraries = doc.NewElement("libraries");
        root->InsertFirstChild(libraries);
    }
    XMLElement* library = doc.NewElement("library");
    library->SetAttribute("name", kLibraryName);
    library->SetAttribute("path", kLibraryFile);
    libraries->InsertEndChild(library);

    XMLElement* effects = root->FirstChildElement("effects");
    if (effects == nullptr) {
        effects = doc.NewElement("effects");
        root->InsertAfterChild(libraries, effects);
    }
    XMLElement* effect = doc.NewElement("effect");
    effect->SetAttribute("name", kEffectName);
    effect->SetAttribute("library", kLibraryName);
    effect->SetAttribute("uuid", SP_EFFECT_IMPL_UUID_STR);
    effects->InsertEndChild(effect);

    if (!options.streams.empty()) {
        XMLElement* postprocess = root->FirstChildElement("postprocess");
        if (postprocess == nullptr) {
            postprocess = doc.NewElement("postprocess");
            root->InsertEndChild(postprocess);
        }
        std::vector<std::string_view> done;
        for (const std::string& type : options.streams) {
            if (std::find(done.begin(), done.end(), type) != done.end()) continue;
            done.push_back(type);
            XMLElement* stream = findChild(postprocess, "stream", "type", type);
            if (stream == nullptr) {
                stream = doc.NewElement("stream");
                stream->SetAttribute("type", type.c_str());
                postprocess->InsertEndChild(stream);
            }
            XMLElement* apply = doc.NewElement("apply");
            apply->SetAttribute("effect", kEffectName);
            stream->InsertEndChild(apply);
        }
    }
    result.xml = print(doc);
    return result;
}

Result remove(std::string_view xml) {
    Result result;
    XMLDocument doc;
    XMLElement* root = load(doc, xml, result.error, result.message);
    if (root == nullptr) return result;
    strip(root);
    result.xml = print(doc);
    return result;
}

Summary inspect(std::string_view xml) {
    Summary summary;
    XMLDocument doc;
    XMLElement* root = load(doc, xml, summary.error, summary.message);
    if (root == nullptr) return summary;
    if (const char* v = root->Attribute("version")) summary.version = v;
    for (XMLElement* s = root->FirstChildElement("libraries"); s; s = s->NextSiblingElement("libraries")) {
        summary.library = summary.library || findChild(s, "library", "name", kLibraryName) != nullptr;
    }
    for (XMLElement* s = root->FirstChildElement("effects"); s; s = s->NextSiblingElement("effects")) {
        summary.effect = summary.effect || findChild(s, "effect", "name", kEffectName) != nullptr ||
                         findChild(s, "effectProxy", "name", kEffectName) != nullptr;
    }
    for (XMLElement* s = root->FirstChildElement("postprocess"); s; s = s->NextSiblingElement("postprocess")) {
        for (XMLElement* st = s->FirstChildElement("stream"); st; st = st->NextSiblingElement("stream")) {
            const char* type = st->Attribute("type");
            if (type != nullptr && findChild(st, "apply", "effect", kEffectName) != nullptr) {
                summary.postprocess.emplace_back(type);
            }
        }
    }
    return summary;
}

const char* toString(Error error) {
    switch (error) {
        case Error::None: return "ok";
        case Error::Parse: return "not well-formed XML";
        case Error::NotEffectsConfig: return "not an audio effects configuration";
        case Error::BadStreamType: return "invalid stream type";
    }
    return "unknown error";
}

}  // namespace sp::xmlpatch
