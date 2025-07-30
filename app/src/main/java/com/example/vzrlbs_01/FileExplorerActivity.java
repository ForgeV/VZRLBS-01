package com.example.vzrlbs_01;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

public class FileExplorerActivity extends AppCompatActivity {

    private ListView fileListView;
    private List<String> fileNames = new ArrayList<>();
    private List<Uri> fileUris = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_explorer);

        Button backButton = findViewById(R.id.back_button);
        backButton.setOnClickListener(v -> finish());

        fileListView = findViewById(R.id.file_list_view);

        Intent intent = getIntent();
        Uri parentUri = intent.getData();

        if (parentUri != null) {
            DocumentFile parentDirectory = DocumentFile.fromTreeUri(this, parentUri);
            if (parentDirectory != null) {
                DocumentFile vzrlbsDir = parentDirectory.findFile("VZRLBS_01");
                if (vzrlbsDir != null && vzrlbsDir.isDirectory()) {
                    List<DocumentFile> files = listFiles(vzrlbsDir);
                    for (DocumentFile file : files) {
                        fileNames.add(file.getName());
                        fileUris.add(file.getUri());
                    }
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, fileNames);
                    fileListView.setAdapter(adapter);
                    fileListView.setOnItemClickListener((parent, view, position, id) -> {
                        Uri fileUri = fileUris.get(position);
                        openFile(fileUri);
                    });
                } else {
                    Toast.makeText(this, "Папка VZRLBS_01 не найдена", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private List<DocumentFile> listFiles(DocumentFile directory) {
        List<DocumentFile> files = new ArrayList<>();
        if (directory != null && directory.isDirectory()) {
            for (DocumentFile file : directory.listFiles()) {
                files.add(file);
            }
        }
        return files;
    }

    private void openFile(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть файл: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}