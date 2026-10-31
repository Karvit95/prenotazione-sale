package it.szn.prenotazionesale.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("AppConfig - Risoluzione dinamica del ruolo amministratore")
class AppConfigTest {

    @Test
    @DisplayName("resolveAdminRole con adminRole null restituisce il valore di default RoomBooking.Admin")
    void testResolveAdminRole_Null_ReturnsDefault() {
        AppConfig appConfig = new AppConfig();
        appConfig.setAdminRole(null);
        assertEquals(AppConfig.DEFAULT_ADMIN_ROLE, appConfig.resolveAdminRole());
    }

    @Test
    @DisplayName("resolveAdminRole con adminRole blank restituisce il valore di default RoomBooking.Admin")
    void testResolveAdminRole_Blank_ReturnsDefault() {
        AppConfig appConfig = new AppConfig();
        appConfig.setAdminRole("   ");
        assertEquals(AppConfig.DEFAULT_ADMIN_ROLE, appConfig.resolveAdminRole());
    }

    @Test
    @DisplayName("resolveAdminRole con valore circondato da spazi restituisce la stringa trimmata")
    void testResolveAdminRole_WithWhitespace_ReturnsTrimmed() {
        AppConfig appConfig = new AppConfig();
        appConfig.setAdminRole("   Custom.Admin   ");
        assertEquals("Custom.Admin", appConfig.resolveAdminRole());
    }

    @Test
    @DisplayName("resolveAdminRole con valore valido configurato restituisce il valore esatto")
    void testResolveAdminRole_ValidConfigured_ReturnsValue() {
        AppConfig appConfig = new AppConfig();
        appConfig.setAdminRole("Global.Administrator");
        assertEquals("Global.Administrator", appConfig.resolveAdminRole());
    }
}

