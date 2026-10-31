package it.szn.prenotazionesale.exception;

/**
 * Eccezione sollevata quando il numero di pagine restituite da Microsoft Graph
 * supera la soglia massima di sicurezza consentita durante la consultazione del calendario.
 */
public class GraphPaginationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GraphPaginationException(String message) {
        super(message);
    }
}

