package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LangPromotionTest {

    private val order = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.ENGLISH)

    @Test fun pickingAnotherLanguageMovesItToTheTop() {
        val allTracks = listOf("Español Latino", "English")
        assertEquals(
            listOf(TrackLang.ENGLISH, TrackLang.LATINO, TrackLang.CASTELLANO),
            LangPromotion.promote(order, "English", allTracks),
        )
    }

    /** The key mitigation: with no alternative there was no choice, so it says nothing about your taste. */
    @Test fun aFileWithASingleLanguageNeverPromotes() {
        assertNull(LangPromotion.promote(order, "English", listOf("English")))
        assertNull(LangPromotion.promote(order, "English", listOf("English", "Audio - [English]")))
    }

    @Test fun anUnknownBucketNeverPromotes() {
        assertNull(LangPromotion.promote(order, "Track 3", listOf("Track 3", "English")))
    }

    @Test fun pickingWhatIsAlreadyOnTopChangesNothing() {
        assertNull(LangPromotion.promote(order, "Español Latino", listOf("Español Latino", "English")))
    }

    @Test fun promotingALanguageNotInTheListAddsIt() {
        val allTracks = listOf("Japanese", "English")
        assertEquals(
            listOf(TrackLang.JAPANESE, TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.ENGLISH),
            LangPromotion.promote(order, "Japanese", allTracks),
        )
    }

    @Test fun subtitlesUseTheFileNameClassifier() {
        val allTracks = listOf("/x/movie.es.srt", "/x/movie.en.srt")
        assertEquals(
            listOf(TrackLang.ENGLISH, TrackLang.LATINO, TrackLang.CASTELLANO),
            LangPromotion.promote(order, "/x/movie.en.srt", allTracks, LangTokens::classifyFileName),
        )
    }
}

class LangOrderEditsTest {

    private val order = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.ENGLISH)

    @Test fun toggleRemovesWhenPresentAndAppendsWhenNot() {
        assertEquals(listOf(TrackLang.LATINO, TrackLang.ENGLISH), LangOrderEdits.toggle(order, TrackLang.CASTELLANO))
        assertEquals(order + TrackLang.JAPANESE, LangOrderEdits.toggle(order, TrackLang.JAPANESE))
    }

    /** It can't be left with no languages: the last one doesn't come out. */
    @Test fun toggleRefusesToEmptyTheList() {
        val single = listOf(TrackLang.LATINO)
        assertEquals(single, LangOrderEdits.toggle(single, TrackLang.LATINO))
    }

    @Test fun moveUpAndDownSwapNeighbours() {
        assertEquals(
            listOf(TrackLang.CASTELLANO, TrackLang.LATINO, TrackLang.ENGLISH),
            LangOrderEdits.moveUp(order, TrackLang.CASTELLANO),
        )
        assertEquals(
            listOf(TrackLang.CASTELLANO, TrackLang.LATINO, TrackLang.ENGLISH),
            LangOrderEdits.moveDown(order, TrackLang.LATINO),
        )
    }

    @Test fun movingPastTheEdgesOrMovingAnAbsentLanguageIsANoOp() {
        assertEquals(order, LangOrderEdits.moveUp(order, TrackLang.LATINO))
        assertEquals(order, LangOrderEdits.moveDown(order, TrackLang.ENGLISH))
        assertEquals(order, LangOrderEdits.moveUp(order, TrackLang.JAPANESE))
    }
}
