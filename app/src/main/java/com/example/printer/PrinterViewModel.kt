package com.example.printer

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel for managing printer UI state
 * 
 * This ViewModel replaces the static companion object pattern in MainActivity
 * to prevent memory leaks and ensure proper lifecycle management.
 */
class PrinterViewModel : ViewModel() {
    
    /**
     * Trigger for refreshing the file list
     * Incremented whenever a new print job is received
     */
    private val _refreshTrigger = MutableStateFlow(0)
    val refreshTrigger: StateFlow<Int> = _refreshTrigger.asStateFlow()
    
    /**
     * Trigger a refresh of the print jobs list
     * Called when a new print job broadcast is received
     */
    fun triggerRefresh() {
        _refreshTrigger.value++
    }
    
    override fun onCleared() {
        super.onCleared()
        // Clean up any resources if needed
    }
}

