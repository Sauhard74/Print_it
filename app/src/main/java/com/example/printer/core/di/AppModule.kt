package com.example.printer.core.di

import android.content.Context
import com.example.printer.data.repositories.PrintJobRepositoryImpl
import com.example.printer.data.repositories.PrinterDiscoveryRepositoryImpl
import com.example.printer.data.repositories.SettingsRepositoryImpl
import com.example.printer.domain.repositories.PrintJobRepository
import com.example.printer.domain.repositories.PrinterDiscoveryRepository
import com.example.printer.domain.repositories.SettingsRepository
import com.example.printer.domain.usecases.*
import com.example.printer.services.printer.PrinterService
import com.example.printer.services.discovery.DiscoveryService

/**
 * Application-level dependency injection module
 * Provides singleton instances of core services and repositories
 */
object AppModule {
    
    // Repositories
    private lateinit var printJobRepository: PrintJobRepository
    private lateinit var printerDiscoveryRepository: PrinterDiscoveryRepository
    private lateinit var settingsRepository: SettingsRepository
    
    // Services
    private lateinit var printerService: PrinterService
    private lateinit var discoveryService: DiscoveryService
    
    // Use Cases
    private lateinit var startPrinterServiceUseCase: StartPrinterServiceUseCase
    private lateinit var stopPrinterServiceUseCase: StopPrinterServiceUseCase
    private lateinit var getPrintJobsUseCase: GetPrintJobsUseCase
    private lateinit var deletePrintJobUseCase: DeletePrintJobUseCase
    private lateinit var deleteAllPrintJobsUseCase: DeleteAllPrintJobsUseCase
    private lateinit var discoverNetworkPrintersUseCase: DiscoverNetworkPrintersUseCase
    private lateinit var getPrinterAttributesUseCase: GetPrinterAttributesUseCase
    private lateinit var updateSettingsUseCase: UpdateSettingsUseCase
    private lateinit var getSettingsUseCase: GetSettingsUseCase
    
    /**
     * Initialize the dependency injection container
     */
    fun initialize(context: Context) {
        // Initialize repositories
        printJobRepository = PrintJobRepositoryImpl(context)
        printerDiscoveryRepository = PrinterDiscoveryRepositoryImpl(context)
        settingsRepository = SettingsRepositoryImpl(context)
        
        // Initialize services
        printerService = PrinterService(context, printJobRepository)
        discoveryService = DiscoveryService(context)
        
        // Initialize use cases
        startPrinterServiceUseCase = StartPrinterServiceUseCase(printerService, settingsRepository)
        stopPrinterServiceUseCase = StopPrinterServiceUseCase(printerService)
        getPrintJobsUseCase = GetPrintJobsUseCase(printJobRepository)
        deletePrintJobUseCase = DeletePrintJobUseCase(printJobRepository)
        deleteAllPrintJobsUseCase = DeleteAllPrintJobsUseCase(printJobRepository)
        discoverNetworkPrintersUseCase = DiscoverNetworkPrintersUseCase(printerDiscoveryRepository)
        getPrinterAttributesUseCase = GetPrinterAttributesUseCase(printerDiscoveryRepository)
        updateSettingsUseCase = UpdateSettingsUseCase(settingsRepository)
        getSettingsUseCase = GetSettingsUseCase(settingsRepository)
    }
    
    // Repository providers
    fun providePrintJobRepository(): PrintJobRepository = printJobRepository
    fun providePrinterDiscoveryRepository(): PrinterDiscoveryRepository = printerDiscoveryRepository
    fun provideSettingsRepository(): SettingsRepository = settingsRepository
    
    // Service providers
    fun providePrinterService(): PrinterService = printerService
    fun provideDiscoveryService(): DiscoveryService = discoveryService
    
    // Use case providers
    fun provideStartPrinterServiceUseCase(): StartPrinterServiceUseCase = startPrinterServiceUseCase
    fun provideStopPrinterServiceUseCase(): StopPrinterServiceUseCase = stopPrinterServiceUseCase
    fun provideGetPrintJobsUseCase(): GetPrintJobsUseCase = getPrintJobsUseCase
    fun provideDeletePrintJobUseCase(): DeletePrintJobUseCase = deletePrintJobUseCase
    fun provideDeleteAllPrintJobsUseCase(): DeleteAllPrintJobsUseCase = deleteAllPrintJobsUseCase
    fun provideDiscoverNetworkPrintersUseCase(): DiscoverNetworkPrintersUseCase = discoverNetworkPrintersUseCase
    fun provideGetPrinterAttributesUseCase(): GetPrinterAttributesUseCase = getPrinterAttributesUseCase
    fun provideUpdateSettingsUseCase(): UpdateSettingsUseCase = updateSettingsUseCase
    fun provideGetSettingsUseCase(): GetSettingsUseCase = getSettingsUseCase
} 