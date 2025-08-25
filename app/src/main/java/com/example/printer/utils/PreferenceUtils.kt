package com.example.printer.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Utility class for managing app preferences
 */
object PreferenceUtils {
    private const val PREFS_NAME = "printer_preferences"
    private const val KEY_PRINTER_NAME = "printer_name"
    private const val DEFAULT_PRINTER_NAME = "Android Virtual Printer"
    
    /**
     * Gets the user-defined printer name or the default name if not set
     */
    fun getCustomPrinterName(context: Context): String {
        val prefs = getPreferences(context)
        return prefs.getString(KEY_PRINTER_NAME, DEFAULT_PRINTER_NAME) ?: DEFAULT_PRINTER_NAME
    }
    
    /**
     * Saves a custom printer name
     */
    fun saveCustomPrinterName(context: Context, name: String) {
        val prefs = getPreferences(context)
        prefs.edit().putString(KEY_PRINTER_NAME, name).apply()
    }
    
    /**
     * Gets the shared preferences instance
     */
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
} 