// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#include <gtest/gtest.h>

#include <vector>

#include "fx_test_util.h"
#include "soundpower/protocol.h"
#include "state_file.h"

namespace sp::fx {
namespace {

using test::golden;

TEST(StateFile, EncodeMatchesGoldenVector) {
    EXPECT_EQ(encodeStateFile(test::exampleParamsV12()), golden("state_file_v1_3"));
}

TEST(StateFile, AppliesTheProtocol12GoldenVector) {
    dsp::ChainParams p;
    const StateFileResult r = applyStateFile(golden("state_file_v1_2"), p);
    ASSERT_EQ(r.error, StateFileError::None) << toString(r.error);
    EXPECT_EQ(r.applied, 6u);
    EXPECT_EQ(r.minor, 2u);
    test::expectSameParams(p, test::exampleParamsV12());
}

TEST(StateFile, AppliesTheProtocol11GoldenVector) {
    dsp::ChainParams p;
    const StateFileResult r = applyStateFile(golden("state_file_v1_1"), p);
    ASSERT_EQ(r.error, StateFileError::None) << toString(r.error);
    EXPECT_EQ(r.applied, 6u);
    EXPECT_EQ(r.skipped, 0u);
    EXPECT_EQ(r.minor, 1u);
    test::expectSameParams(p, test::exampleParamsV11());
}

TEST(StateFile, AppliesGoldenVector) {
    dsp::ChainParams p;
    const StateFileResult r = applyStateFile(golden("state_file_example"), p);
    ASSERT_EQ(r.error, StateFileError::None) << toString(r.error);
    EXPECT_EQ(r.applied, 3u);
    EXPECT_EQ(r.skipped, 0u);
    EXPECT_EQ(r.major, 1u);
    EXPECT_EQ(r.minor, 0u);
    test::expectSameParams(p, test::exampleParams());
}

TEST(StateFile, SkipsUnknownEntriesFromANewerMinorVersion) {
    dsp::ChainParams p;
    const StateFileResult r = applyStateFile(golden("state_file_unknown_entry"), p);
    ASSERT_EQ(r.error, StateFileError::None) << toString(r.error);
    EXPECT_EQ(r.minor, 7u);
    EXPECT_EQ(r.applied, 3u);
    EXPECT_EQ(r.skipped, 1u);
    test::expectSameParams(p, test::exampleParams());
}

TEST(StateFile, EmptyFileWithZeroEntriesIsValid) {
    std::vector<std::uint8_t> f = golden("state_file_example");
    ASSERT_GE(f.size(), 12u);
    f.resize(12);
    f[8] = 0;  // entry_count = 0
    dsp::ChainParams p;
    const StateFileResult r = applyStateFile(f, p);
    EXPECT_EQ(r.error, StateFileError::None);
    EXPECT_EQ(r.applied, 0u);
}

TEST(StateFile, RejectsBadHeaders) {
    dsp::ChainParams p;
    EXPECT_EQ(applyStateFile({}, p).error, StateFileError::BadHeader);
    std::vector<std::uint8_t> f = golden("state_file_example");
    ASSERT_GE(f.size(), 12u);
    EXPECT_EQ(applyStateFile(std::span(f.data(), 11), p).error, StateFileError::BadHeader);
    f[0] = 'X';
    EXPECT_EQ(applyStateFile(f, p).error, StateFileError::BadHeader);

    f = golden("state_file_example");
    ASSERT_GE(f.size(), 12u);
    f[4] = 2;  // major 2
    EXPECT_EQ(applyStateFile(f, p).error, StateFileError::MajorMismatch);
    test::expectSameParams(p, dsp::ChainParams{});
}

TEST(StateFile, EveryTruncationIsRejectedAllOrNothing) {
    const std::vector<std::uint8_t> f = golden("state_file_unknown_entry");
    for (std::size_t n = 12; n < f.size(); ++n) {
        dsp::ChainParams p;
        const StateFileResult r = applyStateFile(std::span(f.data(), n), p);
        EXPECT_EQ(r.error, StateFileError::Truncated) << n;
        test::expectSameParams(p, dsp::ChainParams{});  // nothing applied
    }
}

TEST(StateFile, RejectsTrailingBytes) {
    std::vector<std::uint8_t> f = golden("state_file_example");
    ASSERT_GE(f.size(), 12u);
    f.push_back(0);
    dsp::ChainParams p;
    EXPECT_EQ(applyStateFile(f, p).error, StateFileError::Truncated);
}

TEST(StateFile, RejectsInvalidPayloadOfAKnownId) {
    std::vector<std::uint8_t> f = golden("state_file_example");
    ASSERT_GE(f.size(), 12u);
    // The EQ_STATE entry starts after the header (12) and the MASTER entry (8 + 8); its band
    // count is 8 bytes into the entry.
    f[12 + 16 + 8] = 40;
    dsp::ChainParams p;
    const StateFileResult r = applyStateFile(f, p);
    EXPECT_EQ(r.error, StateFileError::BadEntry);
    EXPECT_EQ(r.failedId, static_cast<std::uint32_t>(SP_PARAM_EQ_STATE));
    test::expectSameParams(p, dsp::ChainParams{});
}

TEST(StateFile, RejectsOversizedFilesAndEntries) {
    std::vector<std::uint8_t> f(SP_STATE_FILE_MAX_BYTES + 1, 0);
    dsp::ChainParams p;
    EXPECT_EQ(applyStateFile(f, p).error, StateFileError::TooLarge);

    f = golden("state_file_example");
    ASSERT_GE(f.size(), 12u);
    f[12 + 4] = 0x01;  // MASTER size = 0x00000401 (> 1 KiB)
    f[12 + 5] = 0x04;
    EXPECT_EQ(applyStateFile(f, p).error, StateFileError::BadEntry);
}

}  // namespace
}  // namespace sp::fx
