package com.v2ray.ang.ui.po0

import android.app.Application
import com.v2ray.ang.dto.Po0ResultState
import com.v2ray.ang.dto.Po0MaintenanceState
import com.v2ray.ang.dto.Po0RunStatus
import com.v2ray.ang.dto.Po0Settings
import com.v2ray.ang.dto.Po0TokenEntry
import com.v2ray.ang.dto.Po0TokenResult
import com.v2ray.ang.dto.Po0WhitelistEntry
import com.v2ray.ang.util.po0TokenId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        var runtimeFailure = false
        var resetFailure = false
        var manualCancellation = false
        var manualGate: CompletableDeferred<Unit>? = null
        var onManualStarted: () -> Unit = {}
        var runtime: Po0RunStatus? = null
        var resets = 0
        var manualStatus = Po0RunStatus(checkedAt = 123, successCount = 1, totalCount = 1)
        var reads = 0
        var writes = 0
        var notifications = 0
        var checks = 0
        var appliedSettings: Po0Settings? = null
        var lastManualSettings: Po0Settings? = null
        var lastQueryOnly: Boolean? = null
        var onApply: () -> Unit = {}
        var onReadStatus: () -> Unit = {}
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
                onReadStatus()
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
                onManualStarted()
                manualGate?.await()
                if (manualCancellation) throw CancellationException("manual child cancelled")
                if (requestFailure) error("request failure")
                lastManualSettings = settings
                lastQueryOnly = queryOnly
                manualStatus
            },
            reportFailure = { operation, _ -> failures.add(operation) },
            probeRuntime = {
                if (runtimeFailure) error("runtime failure")
                runtime
            },
            resetSettings = {
                if (resetFailure) error("reset failure")
                resets++
                stored = Po0Settings()
                status = Po0RunStatus()
                readFailure = false
            }
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
        harness.status = Po0RunStatus(123, 1, 1, true, false, results = listOf(machineResult()))
        harness.viewModel.onAction(Po0Action.RefreshStatus)?.join()
        assertEquals(harness.status, harness.viewModel.uiState.value.status)
        assertEquals("600", harness.viewModel.uiState.value.intervalText)
        assertEquals(listOf(false, true), harness.viewModel.uiState.value.machines.single().rows.map { it.isCurrent })

        harness.statusFailure = true
        harness.viewModel.onAction(Po0Action.RefreshStatus)?.join()
        assertTrue(harness.viewModel.uiState.value.statusReadFailed)
        assertEquals(harness.status, harness.viewModel.uiState.value.status)

        harness.statusFailure = false
        harness.status = Po0RunStatus(456, 0, 1, false, false)
        harness.viewModel.onAction(Po0Action.RefreshStatus)?.join()
        assertFalse(harness.viewModel.uiState.value.statusReadFailed)
        assertEquals(456, harness.viewModel.uiState.value.status.checkedAt)
        assertEquals(1, harness.viewModel.uiState.value.status.totalCount)
        assertEquals(Po0ResultState.UNCHECKED, harness.viewModel.uiState.value.machines.single().result.state)
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

        harness.status = Po0RunStatus(checkedAt = 100, totalCount = 1, running = true, revision = "next-daemon-session")
        harness.viewModel.onAction(Po0Action.RefreshStatus)?.join()
        assertEquals(100, harness.viewModel.uiState.value.status.checkedAt)
        assertEquals("next-daemon-session", harness.viewModel.uiState.value.status.revision)
        assertEquals(Po0ResultState.UNCHECKED, harness.viewModel.uiState.value.machines.single().result.state)
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

    @Test
    fun sameServerModesAreRejectedButConflictingReleasedEntriesCanBeRepaired() = runBlocking {
        val harness = Harness()
        harness.viewModel.onAction(Po0Action.Load)?.join()
        harness.importTokens("$TOKEN@0 $TOKEN@1")
        assertEquals(Po0TokenError.SERVER_CONFLICT, harness.viewModel.uiState.value.tokenEditor?.error)
        assertTrue(harness.viewModel.uiState.value.tokenEntries.isEmpty())
        harness.viewModel.onAction(Po0Action.CancelTokenEdit)
        harness.addToken(TOKEN)
        harness.addToken("$TOKEN@4")
        assertEquals(Po0TokenError.SERVER_CONFLICT, harness.viewModel.uiState.value.tokenEditor?.error)

        val legacy = Harness()
        legacy.stored = Po0Settings(tokenEntries = listOf(Po0TokenEntry(TOKEN), Po0TokenEntry("$TOKEN@4")))
        legacy.viewModel.onAction(Po0Action.Load)?.join()
        assertTrue(legacy.viewModel.uiState.value.loaded)
        assertEquals(2, legacy.viewModel.uiState.value.tokenEntries.size)
        assertFalse(legacy.viewModel.uiState.value.canCheckNow)
        legacy.viewModel.onAction(Po0Action.Save)?.join()
        assertTrue(Po0FormError.CONFLICTING_TOKENS in legacy.viewModel.uiState.value.formErrors)
        assertEquals(0, legacy.writes)
        legacy.viewModel.onAction(Po0Action.RemoveToken(po0TokenId("$TOKEN@4")))
        legacy.viewModel.onAction(Po0Action.Save)?.join()
        assertEquals(listOf(TOKEN), legacy.stored.tokens)
    }

    @Test
    fun pauseIsSavedAndBulkOperationsExcludePausedEntriesWhileSingleQueriesKeepOthers() = runBlocking {
        val harness = Harness()
        harness.stored = Po0Settings(tokenEntries = listOf(Po0TokenEntry(TOKEN, "Home"), Po0TokenEntry(OTHER_TOKEN, "Office")))
        harness.viewModel.onAction(Po0Action.Load)?.join()
        harness.viewModel.onAction(Po0Action.SetTokenPaused(po0TokenId(OTHER_TOKEN), true))
        assertFalse(harness.viewModel.uiState.value.canCheckNow)
        harness.viewModel.onAction(Po0Action.Save)?.join()
        assertTrue(harness.stored.tokenEntries.last().paused)
        harness.manualStatus = Po0RunStatus(checkedAt = 123, results = listOf(machineResult()))
        harness.viewModel.onAction(Po0Action.Query)?.join()
        assertEquals(listOf(TOKEN), harness.lastManualSettings!!.tokens)
        assertEquals(Po0ResultState.PAUSED, harness.viewModel.uiState.value.machines.last().result.state)
        assertEquals(2, harness.viewModel.uiState.value.status.totalCount)
        harness.manualStatus = Po0RunStatus(checkedAt = 100, results = listOf(machineResult().copy(tokenId = po0TokenId(OTHER_TOKEN))))
        harness.viewModel.onAction(Po0Action.QueryToken(po0TokenId(OTHER_TOKEN)))?.join()
        assertEquals(listOf(OTHER_TOKEN), harness.lastManualSettings!!.tokens)
        assertFalse(harness.lastManualSettings!!.tokenEntries.single().paused)
        assertTrue(harness.stored.tokenEntries.last().paused)
        assertEquals(listOf(Po0ResultState.APPLIED, Po0ResultState.APPLIED), harness.viewModel.uiState.value.machines.map { it.result.state })
        assertTrue(harness.viewModel.uiState.value.machines.last().paused)
        assertEquals(100L, harness.viewModel.uiState.value.status.checkedAt)
        harness.viewModel.onAction(Po0Action.RefreshStatus)?.join()
        assertEquals(Po0ResultState.APPLIED, harness.viewModel.uiState.value.machines.last().result.state)
        assertNull(harness.viewModel.onAction(Po0Action.QueryToken("removed-id")))
    }

    @Test
    fun allPausedDisablesBulkButStillAllowsAnExplicitSingleRetry() = runBlocking {
        val harness = Harness()
        harness.stored = Po0Settings(tokenEntries = listOf(Po0TokenEntry(TOKEN, paused = true)))
        harness.manualStatus = Po0RunStatus(checkedAt = 123, results = listOf(machineResult()))
        harness.viewModel.onAction(Po0Action.Load)?.join()
        assertFalse(harness.viewModel.uiState.value.canCheckNow)
        assertNull(harness.viewModel.onAction(Po0Action.CheckNow))
        harness.viewModel.onAction(Po0Action.RetryToken(po0TokenId(TOKEN)))?.join()
        assertEquals(false, harness.lastQueryOnly)
        assertEquals(listOf(TOKEN), harness.lastManualSettings!!.activeTokens)
        assertTrue(harness.stored.tokenEntries.single().paused)
    }

    @Test
    fun daemonRevisionAcceptsNewResultsEvenWhenWallClockMovesBackwards() = runBlocking {
        val harness = Harness()
        harness.stored = Po0Settings(tokenEntries = listOf(Po0TokenEntry(TOKEN)))
        harness.status = Po0RunStatus(checkedAt = 2000, results = listOf(machineResult()), revision = "daemon-one")
        harness.manualStatus = Po0RunStatus(checkedAt = 1000, results = listOf(machineResult().copy(state = Po0ResultState.NOT_APPLIED)))
        harness.viewModel.onAction(Po0Action.Load)?.join()
        harness.viewModel.onAction(Po0Action.RefreshStatus)?.join()
        harness.viewModel.onAction(Po0Action.Query)?.join()
        assertEquals(1000L, harness.viewModel.uiState.value.status.checkedAt)
        assertEquals(Po0ResultState.NOT_APPLIED, harness.viewModel.uiState.value.machines.single().result.state)
        harness.viewModel.onAction(Po0Action.RefreshStatus)?.join()
        assertEquals(1000L, harness.viewModel.uiState.value.status.checkedAt)
        harness.status = harness.status.copy(checkedAt = 500, revision = "daemon-two")
        harness.viewModel.onAction(Po0Action.RefreshStatus)?.join()
        assertEquals(500L, harness.viewModel.uiState.value.status.checkedAt)
        assertEquals(Po0ResultState.APPLIED, harness.viewModel.uiState.value.machines.single().result.state)
    }

    @Test
    fun refreshCapturedBeforeManualRequestCannotReplaceItsNewResult() = runBlocking {
        val harness = Harness()
        harness.stored = Po0Settings(tokenEntries = listOf(Po0TokenEntry(TOKEN)))
        harness.manualStatus = Po0RunStatus(checkedAt = 100, results = listOf(machineResult()))
        harness.viewModel.onAction(Po0Action.Load)?.join()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        var blockFirstRead = true
        harness.onReadStatus = {
            if (blockFirstRead) {
                blockFirstRead = false
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val refresh = harness.viewModel.onAction(Po0Action.RefreshStatus)
        entered.await()
        harness.viewModel.onAction(Po0Action.Query)?.join()
        release.countDown()
        refresh?.join()
        assertEquals(100L, harness.viewModel.uiState.value.status.checkedAt)
        assertEquals(Po0ResultState.APPLIED, harness.viewModel.uiState.value.machines.single().result.state)
    }

    @Test
    fun singleQueriesMergeOtherServersNewDaemonResultsAndKeepEarlierManualOverridesOfTheSameRevision() = runBlocking {
        val harness = Harness()
        harness.stored = Po0Settings(
            tokenEntries = listOf(Po0TokenEntry(TOKEN, "Home"), Po0TokenEntry(OTHER_TOKEN, "Office")),
            storageIdentity = "current-configuration"
        )
        val oldHome = machineResult().copy(currentIp = "192.0.2.1")
        val oldOffice = machineResult().copy(tokenId = po0TokenId(OTHER_TOKEN), currentIp = "198.51.100.1")
        val newOffice = oldOffice.copy(currentIp = "198.51.100.2")
        val manualHome = oldHome.copy(currentIp = "192.0.2.2")
        val manualOffice = oldOffice.copy(currentIp = "198.51.100.3")
        harness.status = Po0RunStatus(results = listOf(oldHome, oldOffice), revision = "old", configurationId = "current-configuration")
        harness.viewModel.onAction(Po0Action.Load)?.join()
        harness.viewModel.onAction(Po0Action.RefreshStatus)?.join()
        harness.onManualStarted = {
            harness.status = harness.status.copy(results = listOf(oldHome, newOffice), revision = "new")
        }
        harness.manualStatus = Po0RunStatus(results = listOf(manualHome))
        harness.viewModel.onAction(Po0Action.QueryToken(po0TokenId(TOKEN)))?.join()
        assertEquals(listOf(manualHome, newOffice), harness.viewModel.uiState.value.status.results)
        harness.viewModel.onAction(Po0Action.RefreshStatus)?.join()
        assertEquals(listOf(manualHome, newOffice), harness.viewModel.uiState.value.status.results)

        harness.onManualStarted = {}
        harness.manualStatus = Po0RunStatus(results = listOf(manualOffice))
        harness.viewModel.onAction(Po0Action.QueryToken(po0TokenId(OTHER_TOKEN)))?.join()
        harness.viewModel.onAction(Po0Action.RefreshStatus)?.join()
        assertEquals(listOf(manualHome, manualOffice), harness.viewModel.uiState.value.status.results)
    }

    @Test
    fun singleQueryDoesNotMergeAnotherConfigurationsBackgroundResults() = runBlocking {
        val harness = Harness()
        harness.stored = Po0Settings(
            tokenEntries = listOf(Po0TokenEntry(TOKEN), Po0TokenEntry(OTHER_TOKEN)),
            storageIdentity = "current-configuration"
        )
        val other = machineResult().copy(tokenId = po0TokenId(OTHER_TOKEN), currentIp = "198.51.100.1")
        harness.status = Po0RunStatus(results = listOf(machineResult(), other), revision = "old", configurationId = "current-configuration")
        harness.viewModel.onAction(Po0Action.Load)?.join()
        harness.viewModel.onAction(Po0Action.RefreshStatus)?.join()
        harness.onManualStarted = {
            harness.status = harness.status.copy(
                results = listOf(other.copy(currentIp = "198.51.100.2")),
                revision = "other", configurationId = "other-configuration"
            )
        }
        harness.manualStatus = Po0RunStatus(results = listOf(machineResult().copy(currentIp = "192.0.2.2")))
        harness.viewModel.onAction(Po0Action.QueryToken(po0TokenId(TOKEN)))?.join()
        assertEquals("192.0.2.2", harness.viewModel.uiState.value.machines.first().result.currentIp)
        assertEquals(other, harness.viewModel.uiState.value.machines.last().result)
    }

    @Test
    fun manualResultsDoNotReviveCredentialsResetReplacedOrUnavailableInAnotherOwner() = runBlocking {
        listOf(false, true).forEach { duringRequest ->
            listOf("reset", "replace", "unavailable").forEach { change ->
                val harness = Harness()
                harness.stored = Po0Settings(tokenEntries = listOf(Po0TokenEntry(TOKEN)), storageIdentity = "original")
                harness.manualStatus = Po0RunStatus(results = listOf(machineResult()))
                harness.viewModel.onAction(Po0Action.Load)?.join()
                val changeConfiguration = {
                    when (change) {
                        "reset" -> harness.stored = Po0Settings()
                        "replace" -> harness.stored = harness.stored.copy(storageIdentity = "replacement")
                        else -> harness.readFailure = true
                    }
                }
                if (duringRequest) harness.onManualStarted = changeConfiguration else changeConfiguration()
                harness.viewModel.onAction(Po0Action.Query)?.join()
                assertEquals(if (duringRequest) 1 else 0, harness.checks)
                assertFalse(harness.viewModel.uiState.value.requestingCheck)
                assertEquals(Po0Message.CHECK_REQUEST_FAILED, harness.viewModel.uiState.value.message)
                assertEquals(Po0ResultState.UNCHECKED, harness.viewModel.uiState.value.machines.single().result.state)
                assertNull(harness.viewModel.uiState.value.machines.single().result.currentIp)
            }
        }
    }

    @Test
    fun runtimeProbeIsIndependentOfStoredHistoryAndResumeDoesNotChangeSettings() = runBlocking {
        val harness = Harness()
        harness.stored = Po0Settings(true, listOf(Po0TokenEntry(TOKEN)), standaloneEnabled = true)
        harness.status = Po0RunStatus(checkedAt = 123, running = true, maintenance = Po0MaintenanceState.CHECKING)
        harness.viewModel.onAction(Po0Action.Load)?.join()
        harness.viewModel.onAction(Po0Action.RefreshStatus)?.join()
        assertTrue(harness.viewModel.uiState.value.runtimeLoaded)
        assertNull(harness.viewModel.uiState.value.runtimeStatus)
        assertTrue(harness.viewModel.uiState.value.canResume)
        harness.viewModel.onAction(Po0Action.ResumeMaintenance)?.join()
        assertEquals(harness.stored, harness.appliedSettings)
        assertEquals(0, harness.writes)
        assertEquals(Po0Message.MAINTENANCE_RESUMED, harness.viewModel.uiState.value.message)
        harness.runtime = Po0RunStatus(maintenance = Po0MaintenanceState.WAITING_NETWORK)
        harness.viewModel.onAction(Po0Action.RefreshStatus)?.join()
        assertEquals(Po0MaintenanceState.WAITING_NETWORK, harness.viewModel.uiState.value.runtimeStatus?.maintenance)
        assertFalse(harness.viewModel.uiState.value.canResume)
        harness.runtimeFailure = true
        harness.viewModel.onAction(Po0Action.RefreshStatus)?.join()
        assertTrue(harness.viewModel.uiState.value.runtimeReadFailed)
        assertFalse(harness.viewModel.uiState.value.canResume)
        harness.runtimeFailure = false
        harness.runtime = null
        harness.viewModel.onAction(Po0Action.RefreshStatus)?.join()
        harness.applyFailure = true
        harness.viewModel.onAction(Po0Action.ResumeMaintenance)?.join()
        assertEquals(Po0Message.SAVED_SERVICE_UNAVAILABLE, harness.viewModel.uiState.value.message)
    }

    @Test
    fun failedCredentialLoadCanOnlyBeResetAfterConfirmationAndResetFailureCanRetry() = runBlocking {
        val harness = Harness()
        harness.readFailure = true
        harness.stored = Po0Settings(tokenEntries = listOf(Po0TokenEntry(TOKEN, "Keep until confirmed")))
        harness.viewModel.onAction(Po0Action.Load)?.join()
        assertNull(harness.viewModel.onAction(Po0Action.ConfirmReset))
        harness.viewModel.onAction(Po0Action.RequestReset)
        harness.viewModel.onAction(Po0Action.CancelReset)
        assertEquals(0, harness.resets)
        assertEquals(listOf(TOKEN), harness.stored.tokens)
        harness.viewModel.onAction(Po0Action.RequestReset)
        harness.resetFailure = true
        harness.viewModel.onAction(Po0Action.ConfirmReset)?.join()
        assertEquals(Po0Message.RESET_FAILED, harness.viewModel.uiState.value.message)
        assertFalse(harness.viewModel.uiState.value.editable)
        assertEquals(listOf(TOKEN), harness.stored.tokens)
        harness.resetFailure = false
        harness.viewModel.onAction(Po0Action.RequestReset)
        harness.viewModel.onAction(Po0Action.ConfirmReset)?.join()
        assertEquals(1, harness.resets)
        assertEquals(Po0Settings(), harness.stored)
        assertTrue(harness.viewModel.uiState.value.editable)
        assertEquals(Po0Message.RESET_COMPLETED, harness.viewModel.uiState.value.message)
        harness.addToken(OTHER_TOKEN, "New")
        harness.viewModel.onAction(Po0Action.Save)?.join()
        assertEquals(listOf(OTHER_TOKEN), harness.stored.tokens)
    }

    @Test
    fun backOffersSaveDiscardOrContinueAndIncludesOpenTokenEditor() = runBlocking {
        val harness = Harness()
        harness.viewModel.onAction(Po0Action.Load)?.join()
        harness.viewModel.onAction(Po0Action.BeginAddToken)
        harness.viewModel.onAction(Po0Action.SetTokenInput(TOKEN))
        harness.viewModel.onAction(Po0Action.SetTokenName("Home"))
        harness.viewModel.onAction(Po0Action.RequestBack)
        assertTrue(harness.viewModel.uiState.value.showExitDialog)
        assertFalse(harness.viewModel.uiState.value.shouldClose)
        harness.viewModel.onAction(Po0Action.ContinueEditing)
        assertFalse(harness.viewModel.uiState.value.showExitDialog)
        assertEquals(TOKEN, harness.viewModel.uiState.value.tokenEditor?.text)
        harness.viewModel.onAction(Po0Action.RequestBack)
        harness.viewModel.onAction(Po0Action.SaveAndExit)?.join()
        assertEquals(listOf(Po0TokenEntry(TOKEN, "Home")), harness.stored.tokenEntries)
        assertTrue(harness.viewModel.uiState.value.shouldClose)

        val discard = Harness()
        discard.viewModel.onAction(Po0Action.Load)?.join()
        discard.viewModel.onAction(Po0Action.SetInterval("900"))
        discard.viewModel.onAction(Po0Action.RequestBack)
        discard.viewModel.onAction(Po0Action.DiscardAndExit)
        assertTrue(discard.viewModel.uiState.value.shouldClose)
        assertEquals(0, discard.writes)
        assertEquals(600, discard.stored.intervalSeconds)
    }

    @Test
    fun saveAndExitValidationAndStorageFailureKeepTheUserOnTheForm() = runBlocking {
        val harness = Harness()
        harness.viewModel.onAction(Po0Action.Load)?.join()
        harness.viewModel.onAction(Po0Action.BeginAddToken)
        harness.viewModel.onAction(Po0Action.SetTokenInput("bad"))
        harness.viewModel.onAction(Po0Action.RequestBack)
        harness.viewModel.onAction(Po0Action.SaveAndExit)?.join()
        assertEquals(Po0TokenError.INVALID, harness.viewModel.uiState.value.tokenEditor?.error)
        assertFalse(harness.viewModel.uiState.value.shouldClose)
        assertEquals(0, harness.writes)
        harness.viewModel.onAction(Po0Action.SetTokenInput(TOKEN))
        harness.saveFailure = true
        harness.viewModel.onAction(Po0Action.SaveAndExit)?.join()
        assertFalse(harness.viewModel.uiState.value.shouldClose)
        assertTrue(harness.viewModel.uiState.value.dirty)
        assertEquals(TOKEN, harness.viewModel.uiState.value.tokenEntries.single().token)
        assertEquals(Po0Message.SAVE_FAILED, harness.viewModel.uiState.value.message)
    }

    @Test
    fun cancelledManualChildUnlocksTheFormAndAllowsAnotherRequest() = runBlocking {
        val harness = Harness()
        harness.stored = Po0Settings(tokenEntries = listOf(Po0TokenEntry(TOKEN)))
        harness.manualStatus = Po0RunStatus(checkedAt = 123, results = listOf(machineResult()))
        harness.viewModel.onAction(Po0Action.Load)?.join()
        harness.manualCancellation = true
        harness.viewModel.onAction(Po0Action.Query)?.join()
        assertFalse(harness.viewModel.uiState.value.requestingCheck)
        assertTrue(harness.viewModel.uiState.value.canOperate)
        assertFalse(harness.failures.contains("request check"))
        harness.manualCancellation = false
        harness.viewModel.onAction(Po0Action.Query)?.join()
        assertEquals(Po0Message.MANUAL_COMPLETED, harness.viewModel.uiState.value.message)
        assertFalse(harness.viewModel.uiState.value.requestingCheck)
    }

    @Test
    fun backCanCloseDuringAManualRequestAndOwnerCancellationReleasesItsLock() = runBlocking {
        val harness = Harness()
        harness.stored = Po0Settings(tokenEntries = listOf(Po0TokenEntry(TOKEN)))
        harness.viewModel.onAction(Po0Action.Load)?.join()
        val entered = CompletableDeferred<Unit>()
        harness.onManualStarted = { entered.complete(Unit) }
        harness.manualGate = CompletableDeferred()
        val request = harness.viewModel.onAction(Po0Action.Query)
        entered.await()
        assertTrue(harness.viewModel.uiState.value.requestingCheck)
        harness.viewModel.onAction(Po0Action.RequestBack)
        assertTrue(harness.viewModel.uiState.value.shouldClose)
        request?.cancelAndJoin()
        assertFalse(harness.viewModel.uiState.value.requestingCheck)
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
