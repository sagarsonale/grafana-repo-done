package com.automation.grafana.model;

public class CreateFolderRequest {

    private String title;

    public CreateFolderRequest() {
    }

    public CreateFolderRequest(String title) {
        this.title = title;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}