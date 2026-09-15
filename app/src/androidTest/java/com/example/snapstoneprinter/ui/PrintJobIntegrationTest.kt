package com.example.snapstoneprinter.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.snapstoneprinter.data.api.ScryfallApiService
import com.example.snapstoneprinter.data.model.ScryfallCard
import com.example.snapstoneprinter.data.repository.CardRepository
import com.example.snapstoneprinter.image.ArtResult
import com.example.snapstoneprinter.image.ArtSource
import com.example.snapstoneprinter.image.PrintSlip
import com.example.snapstoneprinter.image.SlipContent
import com.example.snapstoneprinter.image.SlipRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PrintJobIntegrationTest {
    @Test
    fun historyAdmissionReturnsAcceptedOnly() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val application = ApplicationProvider.getApplicationContext<Application>()
            val api = object : ScryfallApiService {
                override suspend fun getRandomCard(query: String?) = ScryfallCard(name = "Fixture")
                override suspend fun getCardByName(fuzzy: String) = ScryfallCard(name = fuzzy)
            }
            val art = object : ArtSource {
                override suspend fun fetch(url: String): ArtResult = error("No network art expected")
            }
            val renderer = object : SlipRenderer {
                override suspend fun render(
                    plan: List<SlipContent>, art: List<Bitmap?>, contrast: Float, brightness: Float
                ): List<PrintSlip> = plan.map {
                    PrintSlip(Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888), it.name,
                        it.faceIndex, it.totalSlips, it.label)
                }
            }
            val factory = object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(
                    ProxyGeneratorViewModel(application, CardRepository(api), art, renderer)
                )!!
            }
            val vm = ViewModelProvider(store, factory)[ProxyGeneratorViewModel::class.java]
            vm.fetchCardByName("Fixture")
            runCurrent()
            val entry = vm.uiState.value.history.single()

            assertTrue("The first request reserves preparation", vm.tryReprint(entry))
            // No scheduler advance: admission must reserve before export gets a chance to run.
            assertFalse("A second same-turn request must not also be accepted", vm.tryReprint(entry))
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
