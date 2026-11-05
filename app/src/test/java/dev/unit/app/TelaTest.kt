package dev.unit.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelaTest {
    @Test fun separaACopiaDaTelaDoValorDoJogo() {
        // Vampire's Fall 2: a vida achada em 10 lugares; só CharacterStats era do jogo
        assertTrue(daTela("Slider", "UnityEngine.UI"))
        assertTrue(daTela("CharacterBar", ""))
        assertTrue(daTela("TweenerCore`3", "DG.Tweening.Core"))
        assertFalse(daTela("CharacterStats", ""))
        assertFalse(daTela("BattleCharacter", ""))
        assertFalse(daTela("ResourcePoolGenerator", ""))
    }
}
