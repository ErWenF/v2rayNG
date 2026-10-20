package com.v2ray.ang.ui.po0

import android.app.Application
import com.v2ray.ang.dto.Po0ResultState
import com.v2ray.ang.dto.Po0RunStatus
import com.v2ray.ang.dto.Po0Settings
import com.v2ray.ang.dto.Po0TokenEntry
import com.v2ray.ang.dto.Po0TokenResult
import com.v2ray.ang.dto.Po0WhitelistEntry
import com.v2ray.ang.util.po0TokenId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class Po0SettingsViewModelTest {
    private class Harness {
        var stored = Po0Settings()
        var status = Po0RunStatus()
        var readFailure = false
        var saveFailure = false
        var notificationFailure = false
        var applyFailure = false
        var statusFailure = false
        var requestFailure = false
        var manualStatus = Po0RunStatus(checkedAt = 123, successCount = 1, totalCount = 1)
        var reads = 0
        var writes = 0
        var notifications = 0
        var checks = 0
        var appliedSettings: Po0Settings? = null
        var lastManualSettings: Po0Settings? = null
        var lastQueryOnly: Boolean? = null
        var onApply: () -> Unit = {}
        val failures = mutableListOf<String>()
        val saveOperations = mutableListOf<String>()
        val viewModel = Po0SettingsViewModel(
            application = mock<Application>(),
            readSettings = {
                reads++
                if (readFailure) error("read failure")
                stored
            },
            writeSettings = {
                if (saveFailure) error("write failure")
                stored = it
                writes++
                saveOperations.add("write")
            },
            readRunStatus = {
                if (statusFailure) error("status failure")
                status
            },
            applyServices = {
                appliedSettings = it
                saveOperations.add("apply")
                onApply()
                if (applyFailure) error("apply failure")
            },
            notifySettingsChanged = {
                notifications++
                saveOperations.add("notify")
                if (notificationFailure) error("notification failure")
            },
            runManualCheck = { settings, queryOnly ->
                checks++
                if (requestFailure) error("request failure")
                lastManualSettings = settings
                lastQueryOnly = queryOnly
                manualStatus
            },
            reportFailure = { operation, _ -> failures.add(operation) }
        )

        suspend fun addToken(text: String, name: String = "") {
            viewModel.onAction(Po0Action.BeginAddToken)
            viewModel.onAction(Po0Action.SetTokenName(name))
            viewModel.onAction(Po0Action.SetTokenInput(text))
            viewModel.onAction(Po0Action.ConfirmToken)?.join()
        }

        suspend fun importTokens(text: String) {
            viewModel.onAction(Po0Action.BeginImportTokens)
            viewModel.onAction(Po0Action.SetTokenInput(text))
            viewModel.onAction(Po0Action.ConfirmToken)?.join()
        }
    }

    @Test
    fun initialStateLoadsSettingsWithoutChangingThem() = runBlocking {
        val harness = Harness()
        assertTrue(harness.viewModel.uiState.value.loading)
        assertFalse(harness.viewModel.uiState.value.loaded)
        assertFalse(harness.viewModel.uiState.value.editable)
        assertFalse(harness.viewModel.uiState.value.canCheckNow)
        assertTrue(harness.viewModel.uiState.value.machines.isEmpty())
        assertEquals("600", harness.viewModel.uiState.value.intervalText)
        assertNull(harness.viewModel.onAction(Po0Action.Save))
        harness.stored = Po0Settings(true, listOf(Po0TokenEntry(TOKEN, "Home")), 600, false)

        harness.viewModel.onAction(Po0Action.Load)?.join()
        val state = harness.viewModel.uiState.value
        assertFalse(state.loading)
        assertTrue(state.loaded)
        assertTrue(state.enabled)
        assertEquals(TOKEN, state.tokenEntries.single().token)
        assertEquals("Home", state.tokenEntries.single().name)
        assertEquals("600", state.intervalText)
        assertFalse(state.networkChangeEnabled)
        assertFalse(state.dirty)
        assertTrue(state.canCheckNow)
        assertEquals(0, harness.writes)
        assertNull(harness.viewModel.onAction(Po0Action.Load))
        assertEquals(1, harness.reads)
    }

    @Test
    fun loadFailureCanBeRetried() = runBlocking {
        val harness = Harness()
        harness.readFailure = true
        harness.viewModel.onAction(Po0Action.Load)?.join()
        assertFalse(harness.viewModel.uiState.value.loading)
        assertFalse(harness.viewModel.uiState.value.loaded)
        assertEquals(Po0Message.LOAD_FAILED, harness.viewModel.uiState.value.message)

        harness.readFailure = false
        harness.viewModel.onAction(Po0Action.Load)?.join()
        assertTrue(harness.viewModel.uiState.value.loaded)
        assertNull(harness.viewModel.uiState.value.message)
        assertEquals(listOf("load"), harness.failures)
    }

    @Test
    fun successfulSaveNormalizesTokensAndNotifiesOnlyAfterWriting() = runBlocking {
        val harness = Harness()
        harness.viewModel.onAction(Po0Action.Load)?.join()
        harness.importTokens("$TOKEN，$TOKEN；$OTHER_TOKEN")
        harness.viewModel.onAction(Po0Action.SetInterval(" 900 "))
        harness.viewModel.onAction(Po0Action.SetEnabled(true))
        harness.viewModel.onAction(Po0Action.SetNetworkChangeEnabled(false))
        harness.viewModel.onAction(Po0Action.SetStandaloneEnabled(true))
        assertTrue(harness.viewModel.uiState.value.dirty)
        assertFalse(harness.viewModel.uiState.value.canCheckNow)

        harness.viewModel.onAction(Po0Action.Save)?.join()
        assertEquals(
            Po0Settings(true, listOf(Po0TokenEntry(TOKEN), Po0TokenEntry(OTHER_TOKEN)), 900, false, standaloneEnabled = true),
            harness.stored
        )
        assertEquals(harness.stored, harness.appliedSettings)
        assertEquals(1, harness.writes)
        assertEquals(1, harness.notifications)
        assertEquals(listOf("write", "apply", "notify"), harness.saveOperations)
        assertFalse(harness.viewModel.uiState.value.saving)
        assertFalse(harness.viewModel.uiState.value.dirty)
        assertEquals(listOf(TOKEN, OTHER_TOKEN), harness.viewModel.uiState.value.tokenEntries.map { it.token })
        assertEquals("900", harness.viewModel.uiState.value.intervalText)
        assertEquals(Po0Message.SAVED, harness.viewModel.uiState.value.message)
    }

    @Test
    fun enabledEmptyAndMalformedInputDoNotWriteOrNotify() = runBlocking {
        val harness = Harness()
        harness.viewModel.onAction(Po0Action.Load)?.join()
        harness.viewModel.onAction(Po0Action.SetEnabled(true))
        harness.viewModel.onAction(Po0Action.Save)?.join()
        assertEquals(setOf(Po0FormError.TOKENS_REQUIRED), harness.viewModel.uiState.value.formErrors)

        harness.addToken("invalid/token")
        assertEquals(Po0TokenError.INVALID, harness.viewModel.uiState.value.tokenEditor?.error)
        assertNull(harness.viewModel.onAction(Po0Action.Save))
        harness.viewModel.onAction(Po0Action.CancelTokenEdit)
        harness.viewModel.onAction(Po0Action.SetInterval("0"))
        harness.viewModel.onAction(Po0Action.Save)?.join()
        assertTrue(Po0FormError.TOKENS_REQUIRED in harness.viewModel.uiState.value.formErrors)
        assertTrue(Po0FormError.INVALID_INTERVAL in harness.viewModel.uiState.value.formErrors)
        assertEquals(0, harness.writes)
        assertEquals(0, harness.notifications)
        assertTrue(harness.viewModel.uiState.value.dirty)
        assertFalse(harness.viewModel.uiState.value.saving)
    }

    @Test
    fun disabledEmptySettingsCanBeSaved() = runBlocking {
        val harness = Harness()
        harness.viewModel.onAction(Po0Action.Load)?.join()
        harness.viewModel.onAction(Po0Action.Save)?.join()
        assertEquals(Po0Settings(), harness.stored)
        assertEquals(1, harness.writes)
        assertTrue(harness.viewModel.uiState.value.formErrors.isEmpty())
    }

    @Test
    fun intervalValidationAcceptsBoundariesAndRejectsNonIntegers() {
        listOf("1", "3600", " 600 ").forEach { value ->
            assertTrue(validatePo0Form(Po0UiState(intervalText = value)).isEmpty())
        }
        listOf("", "0", "3601", "-5", "2.5", "invalid").forEach { value ->
            assertEquals(setOf(Po0FormError.INVALID_INTERVAL), validatePo0Form(Po0UiState(intervalText = value)))
        }
    }

    @Test
    fun saveFailureKeepsChangesAndDoesNotNotify() = runBlocking {
        val harness = Harness()
        harness.viewModel.onAction(Po0Action.Load)?.join()
        harness.addToken(TOKEN)
        harness.saveFailure = true
        harness.viewModel.onAction(Po0Action.Save)?.join()
        assertEquals(Po0Settings(), harness.stored)
        assertEquals(TOKEN, harness.viewModel.uiState.value.tokenEntries.single().token)
        assertTrue(harness.viewModel.uiState.value.dirty)
        assertFalse(harness.viewModel.uiState.value.saving)
        assertEquals(Po0Message.SAVE_FAILED, harness.viewModel.uiState.value.message)
        assertEquals(0, harness.notifications)
    }

    @Test
    fun notificationFailureDoesNotClaimTheSettingsWereNotSaved() = runBlocking {
        val harness = Harness()
        harness.viewModel.onAction(Po0Action.Load)?.join()
        harness.notificationFailure = true
        harness.viewModel.onAction(Po0Action.Save)?.join()
        assertEquals(1, harness.writes)
        assertFalse(harness.viewModel.uiState.value.dirty)
        assertEquals(Po0Message.SAVED_SERVICE_UNAVAILABLE, harness.viewModel.uiState.value.message)
    }

    @Test
    fun independentServiceFailureStillNotifiesTheProxyAndKeepsSavedSettings() = runBlocking {
        val harness = Harness()
        harness.viewModel.onAction(Po0Action.Load)?.join()
        harness.applyFailure = true
        harness.viewModel.onAction(Po0Action.Save)?.join()
        assertEquals(1, harness.writes)
        assertEquals(1, harness.notifications)
        assertEquals(Po0Message.SAVED_SERVICE_UNAVAILABLE, harness.viewModel.uiState.value.message)
        assertFalse(harness.viewModel.uiState.value.dirty)
    }

    @Test
    fun saveKeepsTheFormLockedUntilTheServiceUpdateFinishes() = runBlocking {
        val harness = Harness()
        harness.viewModel.onAction(Po0Action.Load)?.join()
        harness.onApply = {
            assertTrue(harness.viewModel.uiState.value.saving)
            harness.viewModel.onAction(Po0Action.SetInterval("1"))
            assertNull(harness.viewModel.onAction(Po0Action.Save))
        }
        harness.viewModel.onAction(Po0Action.Save)?.join()
        assertEquals("600", harness.viewModel.uiState.value.intervalText)
        assertEquals(1, harness.writes)
        assertFalse(harness.viewModel.uiState.value.saving)
    }

    @Test
    fun queryAndWhitelistUseSavedSettingsWhileAutomaticWhitelistingIsDisabled() = runBlocking {
        val harness = Harness()
        harness.manualStatus = Po0RunStatus(checkedAt = 123, successCount = 1, totalCount = 1, results = listOf(machineResult()))
        assertNull(harness.viewModel.onAction(Po0Action.CheckNow))
        harness.viewModel.onAction(Po0Action.Load)?.join()
        assertNull(harness.viewModel.onAction(Po0Action.CheckNow))
        harness.addToken(TOKEN)
        assertNull(harness.viewModel.onAction(Po0Action.CheckNow))
        harness.viewModel.onAction(Po0Action.Save)?.join()

        harness.viewModel.onAction(Po0Action.Query)?.join()
        assertEquals(true, harness.lastQueryOnly)
        assertFalse(harness.lastManualSettings!!.enabled)
        assertEquals(harness.stored, harness.lastManualSettings)
        assertEquals(harness.manualStatus, harness.viewModel.uiState.value.status)
        assertEquals(listOf(false, true), harness.viewModel.uiState.value.machines.single().rows.map { it.isCurrent })
        assertEquals(Po0Message.MANUAL_COMPLETED, harness.viewModel.uiState.value.message)

        harness.viewModel.onAction(Po0Action.CheckNow)?.join()
        assertEquals(false, harness.lastQueryOnly)
        assertEquals(Po0Message.MANUAL_COMPLETED, harness.viewModel.uiState.value.message)
        assertFalse(harness.viewModel.uiState.value.requestingCheck)
        assertEquals(2, harness.checks)

        harness.requestFailure = true
        harness.viewModel.onAction(Po0Action.CheckNow)?.join()
        assertEquals(Po0Message.CHECK_REQUEST_FAILED, harness.viewModel.uiState.value.message)
        assertFalse(harness.viewModel.uiState.value.requestingCheck)
    }

    @Test
    fun statusRefreshPreservesDraftAndRecoversAfterFailure() = runBlocking {
        val harness = Harness()
        harness.stored = Po0Settings(tokenEntries = listOf(Po0TokenEntry(TOKEN)))
        harness.viewModel.onAction(Po0Action.Load)?.join()
        harness.viewModel.onAction(Po0Action.SetInterval("600"))
        harness.status = Po0RunStatus(123, 1, 2, true, false, results = listOf(machineResult()))
        harness.viewModel.onAction(Po0Action.RefreshStatus)?.join()
        assertEquals(harness.status, harness.viewModel.uiState.value.status)
        assertEquals("600", harness.viewModel.uiState.value.intervalText)
        assertEquals(listOf(false, true), harness.viewModel.uiState.value.machines.single().rows.map { it.isCurrent })

        harness.statusFailure = true
        harness.viewModel.onAction(Po0Action.RefreshStatus)?.join()
        assertTrue(harness.viewModel.uiState.value.statusReadFailed)
        assertEquals(harness.status, harness.viewModel.uiState.value.status)

        harness.statusFailure = false
        harness.status = Po0RunStatus(456, 2, 2, false, false)
        harness.viewModel.onAction(Po0Action.RefreshStatus)?.join()
        assertFalse(harness.viewModel.uiState.value.statusReadFailed)
        assertEquals(harness.status, harness.viewModel.uiState.value.status)
        assertTrue(harness.viewModel.uiState.value.machines.isEmpty())
    }

    @Test
    fun staleAutomaticStatusDoesNotReplaceTheManualResult() = runBlocking {
        val harness = Harness()
        harness.manualStatus = Po0RunStatus(checkedAt = 123, successCount = 1, totalCount = 1, results = listOf(machineResult()))
        harness.stored = Po0Settings(tokenEntries = listOf(Po0TokenEntry(TOKEN)))
        harness.viewModel.onAction(Po0Action.Load)?.join()
        harness.viewModel.onAction(Po0Action.Query)?.join()
        harness.viewModel.onAction(Po0Action.RefreshStatus)?.join()
        assertEquals(harness.manualStatus, harness.viewModel.uiState.value.status)
        assertEquals(listOf(false, true), harness.viewModel.uiState.value.machines.single().rows.map { it.isCurrent })

        harness.status = Po0RunStatus(checkedAt = harness.manualStatus.checkedAt + 1, totalCount = 1, running = true)
        harness.viewModel.onAction(Po0Action.RefreshStatus)?.join()
        assertEquals(harness.status, harness.viewModel.uiState.value.status)
        assertTrue(harness.viewModel.uiState.value.machines.isEmpty())
    }

    @Test
    fun namedEntriesCanBeAddedRenamedAndRemovedByIdentity() = runBlocking {
        val harness = Harness()
        harness.viewModel.onAction(Po0Action.Load)?.join()
        harness.addToken(TOKEN, "  Home  ")
        harness.addToken("$OTHER_TOKEN@4", "Office")
        var entries = harness.viewModel.uiState.value.tokenEntries
        assertEquals(listOf("Home", "Office"), entries.map { it.name })
        assertEquals(listOf(null, 4), entries.map { it.slot })
        assertFalse(harness.viewModel.uiState.value.canCheckNow)

        val homeId = entries.first().id
        harness.viewModel.onAction(Po0Action.BeginEditToken(homeId))
        harness.viewModel.onAction(Po0Action.SetTokenName("Mobile"))
        harness.viewModel.onAction(Po0Action.ConfirmToken)?.join()
        assertEquals(homeId, harness.viewModel.uiState.value.tokenEntries.first().id)
        harness.viewModel.onAction(Po0Action.RemoveToken(entries.last().id))
        harness.viewModel.onAction(Po0Action.Save)?.join()
        assertEquals(listOf(Po0TokenEntry(TOKEN, "Mobile")), harness.stored.tokenEntries)
        assertEquals(listOf(TOKEN), harness.appliedSettings!!.tokens)
        assertTrue(harness.viewModel.uiState.value.canCheckNow)

        // A delayed action for a removed entry cannot edit the remaining server.
        entries = harness.viewModel.uiState.value.tokenEntries
        harness.viewModel.onAction(Po0Action.BeginEditToken(po0TokenId("$OTHER_TOKEN@4")))
        harness.viewModel.onAction(Po0Action.RemoveToken(po0TokenId("$OTHER_TOKEN@4")))
        assertNull(harness.viewModel.uiState.value.tokenEditor)
        assertEquals(entries, harness.viewModel.uiState.value.tokenEntries)
        assertFalse(harness.viewModel.uiState.value.dirty)
    }

    @Test
    fun tokenEditorRejectsEmptyMalformedMultipleAndDuplicateWithoutChangingEntries() = runBlocking {
        val harness = Harness()
        harness.stored = Po0Settings(tokenEntries = listOf(Po0TokenEntry(TOKEN, "Home")))
        harness.viewModel.onAction(Po0Action.Load)?.join()
        listOf(
            "" to Po0TokenError.REQUIRED,
            "invalid/token" to Po0TokenError.INVALID,
            "$TOKEN，$OTHER_TOKEN" to Po0TokenError.MULTIPLE,
            TOKEN to Po0TokenError.DUPLICATE
        ).forEach { (input, error) ->
            harness.addToken(input, "Other")
            assertEquals(error, harness.viewModel.uiState.value.tokenEditor?.error)
            assertFalse(harness.viewModel.uiState.value.canCheckNow)
            assertNull(harness.viewModel.onAction(Po0Action.Save))
            assertNull(harness.viewModel.onAction(Po0Action.Query))
            assertEquals(listOf(TOKEN), harness.viewModel.uiState.value.tokenEntries.map { it.token })
            harness.viewModel.onAction(Po0Action.CancelTokenEdit)
        }
        assertTrue(harness.viewModel.uiState.value.canCheckNow)
        assertFalse(harness.viewModel.uiState.value.dirty)
        assertEquals(0, harness.writes)
        assertEquals(0, harness.checks)
    }

    @Test
    fun bulkImportKeepsExistingNamesAndSplitsLinksIntoIndependentEntries() = runBlocking {
        val harness = Harness()
        harness.stored = Po0Settings(tokenEntries = listOf(Po0TokenEntry(TOKEN, "Home")))
        harness.viewModel.onAction(Po0Action.Load)?.join()
        harness.importTokens("$TOKEN，https://124.221.69.228/api/firewall/$OTHER_TOKEN/add?slot=2")
        assertNull(harness.viewModel.uiState.value.tokenEditor)
        assertEquals(listOf("Home", ""), harness.viewModel.uiState.value.tokenEntries.map { it.name })
        assertEquals(listOf(TOKEN, "$OTHER_TOKEN@2"), harness.viewModel.uiState.value.tokenEntries.map { it.token })
        harness.viewModel.onAction(Po0Action.Save)?.join()

        harness.importTokens(TOKEN)
        assertEquals(Po0TokenError.DUPLICATE, harness.viewModel.uiState.value.tokenEditor?.error)
        harness.viewModel.onAction(Po0Action.CancelTokenEdit)
        harness.importTokens("$OTHER_TOKEN，bad")
        assertEquals(Po0TokenError.INVALID, harness.viewModel.uiState.value.tokenEditor?.error)
        assertEquals(2, harness.viewModel.uiState.value.tokenEntries.size)
    }

    @Test
    fun bothManualOperationsUseEverySavedTokenAndMatchResultsToNames() = runBlocking {
        val harness = Harness()
        harness.stored = Po0Settings(tokenEntries = listOf(Po0TokenEntry(TOKEN, "Home"), Po0TokenEntry(OTHER_TOKEN, "Office")))
        harness.manualStatus = Po0RunStatus(
            checkedAt = 123, successCount = 1, totalCount = 2, error = true,
            results = listOf(
                machineResult().copy(tokenId = po0TokenId(OTHER_TOKEN), state = Po0ResultState.REJECTED),
                machineResult()
            )
        )
        harness.viewModel.onAction(Po0Action.Load)?.join()
        listOf(Po0Action.Query, Po0Action.CheckNow).forEach { action ->
            harness.viewModel.onAction(action)?.join()
            assertEquals(listOf(TOKEN, OTHER_TOKEN), harness.lastManualSettings!!.tokens)
            assertEquals(listOf("Office", "Home"), harness.viewModel.uiState.value.machines.map { it.name })
            assertEquals(2, harness.viewModel.uiState.value.status.totalCount)
            assertTrue(harness.viewModel.uiState.value.status.error)
        }
        assertEquals(2, harness.checks)
        assertEquals(false, harness.lastQueryOnly)

        harness.viewModel.onAction(Po0Action.BeginEditToken(po0TokenId(TOKEN)))
        harness.viewModel.onAction(Po0Action.SetTokenName("Mobile"))
        harness.viewModel.onAction(Po0Action.ConfirmToken)?.join()
        // An uncommitted name cannot change the label on the last saved result.
        assertEquals(listOf("Office", "Home"), harness.viewModel.uiState.value.machines.map { it.name })
        harness.viewModel.onAction(Po0Action.Save)?.join()
        assertEquals(listOf("Office", "Mobile"), harness.viewModel.uiState.value.machines.map { it.name })
        harness.viewModel.onAction(Po0Action.RemoveToken(po0TokenId(OTHER_TOKEN)))
        harness.viewModel.onAction(Po0Action.Save)?.join()
        assertEquals(listOf("Mobile"), harness.viewModel.uiState.value.machines.map { it.name })
        assertEquals(1, harness.viewModel.uiState.value.status.totalCount)
        assertEquals(1, harness.viewModel.uiState.value.status.successCount)
        assertFalse(harness.viewModel.uiState.value.status.error)
    }

    @Test
    fun editingTokenPreservesNameButUsesTheNewCredentialIdentity() = runBlocking {
        val harness = Harness()
        harness.stored = Po0Settings(tokenEntries = listOf(Po0TokenEntry(TOKEN, "Home"), Po0TokenEntry(OTHER_TOKEN, "Office")))
        harness.viewModel.onAction(Po0Action.Load)?.join()
        harness.viewModel.onAction(Po0Action.BeginEditToken(po0TokenId(TOKEN)))
        harness.viewModel.onAction(Po0Action.SetTokenInput(OTHER_TOKEN))
        harness.viewModel.onAction(Po0Action.ConfirmToken)?.join()
        assertEquals(Po0TokenError.DUPLICATE, harness.viewModel.uiState.value.tokenEditor?.error)
        harness.viewModel.onAction(Po0Action.SetTokenInput("https://124.221.69.228/api/firewall/$TOKEN/add?slot=3"))
        harness.viewModel.onAction(Po0Action.ConfirmToken)?.join()
        val edited = harness.viewModel.uiState.value.tokenEntries.first()
        assertEquals("Home", edited.name)
        assertEquals("$TOKEN@3", edited.token)
        assertEquals(po0TokenId("$TOKEN@3"), edited.id)
        harness.viewModel.onAction(Po0Action.Save)?.join()
        assertEquals(listOf("$TOKEN@3", OTHER_TOKEN), harness.stored.tokens)
    }

    companion object {
        private const val TOKEN = "pgnfw_ui_test_a"
        private const val OTHER_TOKEN = "pgnfw_ui_test_b"

        private fun machineResult() = Po0TokenResult(
            tokenId = po0TokenId(TOKEN),
            state = Po0ResultState.APPLIED,
            currentIp = "192.0.2.42",
            whitelist = listOf("203.0.113.0/24", "192.0.2.0/24"),
            entries = listOf(Po0WhitelistEntry("203.0.113.0/24", 4), Po0WhitelistEntry("192.0.2.0/24"))
        )
    }
}
