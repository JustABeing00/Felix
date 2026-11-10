package com.felix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsFreeModelsTest {

    private class Recorder(
        private val name: String,
        private val log: MutableList<String>,
        private val failure: Throwable? = null
    ) : Releasable {
        var releases = 0

        override fun release() {
            releases++
            log += name
            if (failure != null) throw failure
        }
    }

    @Test fun resourcesAreReleasedOnceEachNewestFirst() {
        val log = mutableListOf<String>()
        val group = ResourceGroup()
        val a = Recorder("a", log)
        val b = Recorder("b", log)
        group.add(a)
        group.add(b)
        assertTrue(group.releaseAll().isEmpty())
        assertEquals(listOf("b", "a"), log)
        assertTrue(group.releaseAll().isEmpty())
        assertEquals(1, a.releases)
        assertEquals(1, b.releases)
    }

    @Test fun oneFailingReleaseDoesNotStopTheOthers() {
        val log = mutableListOf<String>()
        val group = ResourceGroup()
        group.add(Recorder("a", log))
        group.add(Recorder("b", log, IllegalStateException("boom")))
        val failures = group.releaseAll()
        assertEquals(1, failures.size)
        assertEquals(listOf("b", "a"), log)
    }

    @Test fun resourceAddedAfterReleaseIsReleasedImmediately() {
        val log = mutableListOf<String>()
        val group = ResourceGroup()
        group.releaseAll()
        group.add(Recorder("late", log))
        assertEquals(listOf("late"), log)
    }

    @Test fun setupIsReadyOnlyWhenModelAndRecognizerAreAvailable() {
        assertTrue(SetupReport(speechModelPresent = true, commandRecognizerAvailable = true).ready)
        assertTrue(SetupReport(speechModelPresent = true, commandRecognizerAvailable = true).problems().isEmpty())
    }

    @Test fun setupExplainsAMissingWakeWordModel() {
        val problems = SetupReport(speechModelPresent = false, commandRecognizerAvailable = true).problems()
        assertEquals(1, problems.size)
        assertTrue(problems[0].startsWith("Wake-word model not configured"))
    }

    @Test fun setupExplainsMissingSpeechRecognition() {
        val report = SetupReport(speechModelPresent = true, commandRecognizerAvailable = false)
        assertTrue(!report.ready)
        assertTrue(report.problems().single().startsWith("Speech recognition isn't available"))
    }
}
