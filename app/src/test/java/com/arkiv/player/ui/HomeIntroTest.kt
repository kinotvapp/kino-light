package com.arkiv.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeIntroTest {
    @Test fun `the Home mini guide copy is the spec's`() {
        assertEquals("Tu contenido", HOME_INTRO_CONTENT_TITLE)
        assertEquals("Acá vas a ver lo que traen tus plugins instalados.", HOME_INTRO_CONTENT_BODY)
        assertEquals("El menú", HOME_INTRO_MENU_TITLE)
        assertEquals("Categorías, Biblioteca, Plugins y Ajustes están acá.", HOME_INTRO_MENU_BODY)
        assertEquals("Buscar", HOME_INTRO_SEARCH_TITLE)
        assertEquals("Toca la lupa para buscar algo puntual.", HOME_INTRO_SEARCH_BODY)
    }
}
