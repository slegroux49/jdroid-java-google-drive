package com.jdroid.java.google.drive;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.googleapis.media.MediaHttpUploaderProgressListener;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.ParentReference;
import com.jdroid.java.exception.UnexpectedException;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class GoogleDriveHelper {

	public static final String FOLDER_MIME_TYPE = "application/vnd.google-apps.folder";

	private FileDataStoreFactory dataStoreFactory;
	private JsonFactory jsonFactory;
	private HttpTransport httpTransport;

	// Scopes required by this quickstart.
	private List<String> scopes;

	private class CustomProgressListener implements MediaHttpUploaderProgressListener {

		public void progressChanged(MediaHttpUploader uploader) throws IOException {
			switch (uploader.getUploadState()) {
				case INITIATION_STARTED:
					System.out.println("File upload initiation has started!");
					break;
				case INITIATION_COMPLETE:
					System.out.println("File upload initiation is complete!");
					break;
				case MEDIA_IN_PROGRESS:
					System.out.println("File upload progress: " + uploader.getProgress());
					break;
				case MEDIA_COMPLETE:
					System.out.println("Upload is complete!");
			}
		}
	}

	private String applicationName;
	private Drive drive;
	private String userCredentialsDirPath;

	public GoogleDriveHelper(List<String> scopes, String userCredentialsDirPath) throws IOException {

		jsonFactory = JacksonFactory.getDefaultInstance();
		try {
			httpTransport = GoogleNetHttpTransport.newTrustedTransport();
		} catch (GeneralSecurityException e) {
			throw new UnexpectedException(e);
		}
		dataStoreFactory = new FileDataStoreFactory(new java.io.File(userCredentialsDirPath));

		this.scopes = scopes;
		this.userCredentialsDirPath = userCredentialsDirPath;
		drive = getDriveService();
	}

	/**
	 * Build and return an authorized Drive client service.
	 *
	 * @return an authorized Drive client service
	 * @throws IOException
	 */
	private Drive getDriveService() throws IOException {
		Credential credential = authorize();
		Drive.Builder builder = new Drive.Builder(httpTransport, jsonFactory, credential);
		builder.setApplicationName(applicationName);
		return builder.build();
	}

	/**
	 * Creates an authorized Credential object.
	 *
	 * @return an authorized Credential object.
	 * @throws IOException
	 */
	private Credential authorize() throws IOException {
		// Load client secrets.
		InputStream in = new FileInputStream(userCredentialsDirPath + "/client_secret.json");
		GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(jsonFactory, new InputStreamReader(in));

		// Build flow and trigger user authorization request.
		GoogleAuthorizationCodeFlow.Builder builder = new GoogleAuthorizationCodeFlow.Builder(
				httpTransport, jsonFactory, clientSecrets, scopes);
		builder.setDataStoreFactory(dataStoreFactory);
		builder.setAccessType("offline");
		GoogleAuthorizationCodeFlow flow = builder.build();
		return new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");
	}

	public void createFolders(List<String> folders) throws IOException {
		String parentId = "root";
		for (String folderName : folders) {
			File folder = createFolder(folderName, parentId);
			parentId = folder.getId();
		}
	}

	public File createFolder(String folderName, String parentId) throws IOException {
		File folder = locateFile(folderName, FOLDER_MIME_TYPE, parentId);
		if (folder == null) {
			File metaData = new File();
			metaData.setTitle(folderName);
			metaData.setMimeType(FOLDER_MIME_TYPE);

			if (parentId != null) {
				metaData.setParents(Arrays.asList(new ParentReference().setId(parentId)));
			}

			folder = drive.files().insert(metaData).execute();
		}
		return folder;
	}

	public void uploadFile(String mimeType, String sourcePath, List<String> targetPaths) throws IOException {
		File parent = locateParent(targetPaths);
		uploadFile(mimeType, sourcePath, parent);
	}

	public void uploadFile(String mimeType, String sourcePath, File parent) throws IOException {

		java.io.File contentFile = new java.io.File(sourcePath);
		File fileToRemove = locateFile(contentFile.getName(), mimeType, parent.getId());
		if (fileToRemove != null) {
			drive.files().delete(fileToRemove.getId()).execute();
		}

		InputStreamContent mediaContent = new InputStreamContent(mimeType, new BufferedInputStream(new FileInputStream(contentFile)));
		mediaContent.setLength(contentFile.length());

		File metaData = new File();
		metaData.setTitle(contentFile.getName());
		metaData.setMimeType(mimeType);

		if (parent != null) {
			metaData.setParents(Arrays.asList(new ParentReference().setId(parent.getId())));
		}

		Drive.Files.Insert request = drive.files().insert(metaData, mediaContent);
		request.getMediaHttpUploader().setProgressListener(new CustomProgressListener());
		request.execute();
	}

	public List<File> listFiles() throws IOException  {
		List<File> result = new ArrayList<File>();
		Drive.Files.List request = drive.files().list();

		do {
			try {
				FileList files = request.execute();
				result.addAll(files.getItems());
				request.setPageToken(files.getNextPageToken());
			} catch (IOException e) {
				System.out.println("An error occurred: " + e);
				request.setPageToken(null);
			}
		} while (request.getPageToken() != null &&
				request.getPageToken().length() > 0);

		return result;
	}

	public File locateFile(String title, String mimeType, String parentId) throws IOException {
		StringBuilder query = new StringBuilder();
		query.append("(title='");
		query.append(title);
		query.append("') and (mimeType = '");
		query.append(mimeType);
		query.append("') and (not trashed) and ('");
		query.append(parentId);
		query.append("' in parents)");

		FileList fileList = drive.files().list().setQ(query.toString()).execute();
		return (fileList != null && fileList.getItems() != null && !fileList.getItems().isEmpty()) ? fileList.getItems().get(0) : null;
	}

	public File locateParent(List<String> paths) throws IOException {
		File result = null;
		if (paths != null) {
			for (String path : paths) {
				final StringBuilder query = new StringBuilder();

				query.append("(title='");
				query.append(path);
				query.append("') and (mimeType='application/vnd.google-apps.folder') and (not trashed)");

				if (result != null) {
					query.append(" and ('");
					query.append(result.getId());
					query.append("' in parents)");
				} else {
					query.append(" and ('root' in parents)");
				}

				final FileList fileList = drive.files().list().setQ(query.toString()).execute();

				if ((fileList == null) || (fileList.getItems() == null) || (fileList.getItems().isEmpty())) {
					throw new IllegalArgumentException("Invalid Google Drive path. Forgot to create folders?");
				}
				result = fileList.getItems().get(0);
			}
		}

		if (result == null) {
			throw new IllegalArgumentException("Invalid Google Drive path.");
		}
		return result;
	}

	public void setApplicationName(String applicationName) {
		this.applicationName = applicationName;
	}
}
