package com.otoniel.nightplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class IntentParserTest {

    @Test
    fun `titulo a partir del nombre de fichero que manda Stremio`() {
        assertEquals(
            "Big Buck Bunny (2008)",
            IntentParser.titleFromFileName("Big.Buck.Bunny(2008)[MULTI ESP-ENG-FRE][HDR10 HEVC][Bluray 1080p]-Group.mkv"),
        )
        assertEquals("My Holiday Video 2019 1080p", IntentParser.titleFromFileName("My_Holiday_Video_2019_1080p.mp4"))
        assertEquals("video", IntentParser.titleFromFileName("video"))
    }
}
