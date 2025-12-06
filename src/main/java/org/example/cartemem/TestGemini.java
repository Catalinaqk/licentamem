package org.example.cartemem;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class TestGemini {
    public static void main(String[] args) throws Exception {
        // Cheia ta (cea din mesajul tau recent)
        String key = "AIzaSyAOAwp4W5F9i34SxW4ZBabY9VXlasnebFU";

        // Cerem lista tuturor modelelor disponibile pentru aceasta cheie
        String url = "https://generativelanguage.googleapis.com/v1beta/models?key=" + key;

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();

        System.out.println("⏳ Întreb Google ce modele sunt disponibile pentru cheia ta...");
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println("Status: " + response.statusCode());
        System.out.println("Răspuns: " + response.body());
    }
}