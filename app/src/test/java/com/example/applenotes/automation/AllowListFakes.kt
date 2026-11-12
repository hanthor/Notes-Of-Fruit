package com.example.applenotes.automation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

internal const val TASKER = "net.dinglisch.android.taskerm"
internal val TASKER_CERT = "ab".repeat(32)
internal val OTHER_CERT = "cd".repeat(32)

internal class InMemoryAllowListStore(initial: AllowListState = AllowListState()) : AllowListStore {
    private val flow = MutableStateFlow(initial)
    var writes = 0
        private set

    override val state: Flow<AllowListState> = flow

    override suspend fun update(transform: (AllowListState) -> AllowListState): AllowListState {
        writes++
        return transform(flow.value).also { flow.value = it }
    }

    val value: AllowListState get() = flow.value
}

/** PackageManager stand-in: only the given packages are installed and visible. */
internal class FakePackages(vararg callers: VerifiedCaller) : PackageLookup {
    val installed = callers.associateBy { it.packageName }.toMutableMap()

    override fun verify(packageName: String): VerifiedCaller? = installed[packageName]
}
