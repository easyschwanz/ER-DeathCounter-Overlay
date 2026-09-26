package autodeathcounter;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
//import java.util.logging.Level;
//import java.util.logging.Logger;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.util.Duration;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

public class Overlay extends Application implements NativeKeyListener {
    // Constants
	private static final File CONFIG_DIR = PathsUtil.resolveConfigDir();
    private static final File OVERLAY_CONF_FILE = new File(CONFIG_DIR, "overlay.conf");
    private static final File DEFAULT_ER_DIR = PathsUtil.resolveEldenRingDir();
    private static final File SAVE_FILE_DIR = new File(CONFIG_DIR, "savefile.conf");
    private static final File LAST_SLOT_DIR = new File(CONFIG_DIR, "last_used_slot.conf");
    
    // DeathCount-Related
    private List<SaveProfile> profiles = new ArrayList<>();
    private int currentFocusedSlot = -1;
    private boolean writeLastSlot;

    // Timer
    private ScheduledExecutorService saveFileWatcher;
    private FileTime lastModifiedTime;
    
    // JavaFx - Overlay
    private Label focusedSlotLabel = new Label("No Slot selected");
    private Label deathCountLabel = new Label("Deaths: -");
    private int lastOverlayDeaths = -1;
    private Stage overlayStage = null;
    private VBox root;
    
    // Overlay - Config
    private double baseWidth;
	private double baseHeight;
	private double scale = 1.0;
	private static final double BASE_FONT_SIZE_BIG = 28;
	private static final double BASE_FONT_SIZE_SMALL = 10;
    
    // JavaFx - SlotSelection
	private Stage slotStage = null;
    private ComboBox<Integer> slotSelector;
    private final Label nameLabel = new Label("Name: \t\t-");
    private final Label levelLabel = new Label("Level: \t\t-");
    private final Label timeLabel = new Label("Playtime: \t\t-");
    private final Label deathLabel = new Label("Deaths: \t\t-");
    private static final Label INFO_LABEL = new Label(
    		"-------------------------------------------------------------------------" +
    		"\nHOTKEY INFO:" + "\n" +
    		"\nMove Overlay \nUp/Down/Left/Right: \t\t" + NativeKeyEvent.getKeyText(NativeKeyEvent.VC_CONTROL) + " and " + "🠕" + "/" +
			"🠗" + "/" + "🠔" +
			"/" + "🠖" + " or \n\t\t\t\t\t\thold ALT and drag with mouse" + "\n" +
			"\nChange Opacity +/-: \t\t" + NativeKeyEvent.getKeyText(NativeKeyEvent.VC_CONTROL)+ " and " + NativeKeyEvent.getKeyText(NativeKeyEvent.VC_T) + 
			"/" + NativeKeyEvent.getKeyText(NativeKeyEvent.VC_R) + "\n" +
			"\nOpen or Close Slot Viewer: \t" + NativeKeyEvent.getKeyText(Hotkeys.OPEN_CONFIG_KEY) +
			"\n\nResize +/-: \t\t\t\t" + NativeKeyEvent.getKeyText(NativeKeyEvent.VC_CONTROL) + " and +/-" +
			"\n\nClosing the Overlay: \t\t" + NativeKeyEvent.getKeyText(Hotkeys.CLOSE_OVERLAY_KEY) +
            "\n\nMissed deaths: \t\tAutomatically detected from the YOU DIED screen" +
            "\n\t\t\t\tand reconciled with the save counter"
			+ "\n-------------------------------------------------------------------------"
    		);

    // Save-File
    private File saveFile = null;
    
    // Automatic fallback for deaths that Elden Ring does not add to its save counter.
    private final Map<Integer, Integer> fallbackDeaths = new ConcurrentHashMap<>();
    private DeathDetector deathDetector;

    // A detected death is reconciled against the save counter before a fallback +1 is added.
    private volatile long pendingDeathToken = 0;
    private volatile int pendingDeathSlot = -1;
    private volatile int pendingRawDeaths = -1;
    private static final int FINAL_DEATH_RECONCILE_SECONDS = 12;

    //Hotkeys
    private Hotkeys hotkeys;
    
public void start(Stage primaryStage) throws Exception {
        //Init-Call----------------------------------------------\\
        saveFile = loadSaveFileDir();
        if(saveFile == null) {
        	saveFile = openSaveFileDialog(primaryStage);        	
        }
        if(saveFile != null) {
        		saveFileDir(saveFile);
        } else {
        	saveFile = null;
        }
        
        loadFallbackDeaths();
        writeLastSlot = loadLastSlot();

        Parser parser = new Parser();
        profiles = parser.extractProfiles(saveFile);

        if (profiles.isEmpty()) {
            Platform.runLater(() -> {
                focusedSlotLabel.setText("No Slot selected");
                deathCountLabel.setText("Deaths: -");
            });
        }

        if (saveFile != null && saveFile.exists()) {
            startFileWatcher(parser);
        }

        startOverlay();
        startDeathDetector();

        createSlotSelection();
        slotStage.show();
        
//        Logger logger = Logger.getLogger(GlobalScreen.class.getPackage().getName()); //Debug
//        logger.setLevel(Level.OFF);
//        logger.setUseParentHandlers(false);
        
        try {
        	if(!GlobalScreen.isNativeHookRegistered()) {
        		GlobalScreen.registerNativeHook();
        	}
        	hotkeys = new Hotkeys(this);
        	GlobalScreen.addNativeKeyListener(hotkeys);
        } catch (NativeHookException e) {
        	e.printStackTrace();
        }
        
        overlayStage.setOnCloseRequest(e  -> {
        	cleanUpAndExit();
        });
        
        if(slotStage != null) {
        	slotStage.setOnCloseRequest(e -> {
        		slotStage = null;
        	});
        }
    }

    
	public void stop() {
    	cleanUpAndExit(false);
    }
	
    public void shutdownExecutors() {
        if (deathDetector != null) {
            deathDetector.stop();
        }
        if (saveFileWatcher != null) {
            saveFileWatcher.shutdownNow();
        }
        if (writeLastSlot) {
        	saveLastSlot(getCurrentFocusedSlot());
        }
        try {
    		Thread.sleep(1000);
    	} catch (InterruptedException ie) {
    		ie.printStackTrace();
    	}
    }
    
    public void toggleSlotSelection() {
        if (slotStage == null) {
           slotStage = createSlotSelectionStage();
        }
        
        if (slotStage.isShowing()) {
            slotStage.hide();
        } else {
            slotStage.show();
            slotStage.toFront();
        }
    }
    
    public void moveOverlay(double dx, double dy) {
		if(overlayStage != null) {
			Platform.runLater(() -> {				
				overlayStage.setX(overlayStage.getX() + dx);
				overlayStage.setY(overlayStage.getY() + dy);
				saveOverlayConfig();
			});
		}
	}
    
    public void changeScale(double delta) {
	    scale = Math.max(0.5, Math.min(10.0, scale + delta));
	    
	    Platform.runLater(() -> {	        
	        double newWidth = baseWidth * scale;
	        double newHeight = baseHeight * scale;
	        
	        root.setPrefWidth(newWidth);
	        root.setPrefHeight(newHeight);
	        
	        double bigFontSize = BASE_FONT_SIZE_BIG*scale;
	        double smallFontSize = BASE_FONT_SIZE_SMALL*scale;
	        
	        deathCountLabel.setFont(Font.font("Prince Valiant", FontWeight.BOLD, bigFontSize));
	        focusedSlotLabel.setFont(Font.font("Prince Valiant", smallFontSize));
	        
	        overlayStage.sizeToScene();
	        
	        saveOverlayConfig();
	    });
	}
    
    public void changeOpacity(double delta) {
		double current = overlayStage.getOpacity();
		double newOpacity = Math.min(1.0, Math.max(0.1, current + delta));
		Platform.runLater(() -> {
			overlayStage.setOpacity(newOpacity);	
			saveOverlayConfig();
		});
	}
    
    //Helpers-------------------------------------------------------------------------------------\\
    private void cleanUpAndExit() {
    	cleanUpAndExit(true);
    }
    
    private void cleanUpAndExit(boolean exitJvm) {
        if (deathDetector != null) {
            deathDetector.stop();
        }
    	if(saveFileWatcher != null) {
    		saveFileWatcher.shutdownNow();
    	}
    	
    	try {
    		if(GlobalScreen.isNativeHookRegistered()) {
    			GlobalScreen.unregisterNativeHook();
    		}
    	} catch (Exception ex) {
    		ex.printStackTrace();
    	}
    	
    	if(exitJvm) {
    		System.exit(0);
    	}
    }
    
	private void createSlotSelection() {
		if(slotStage != null) return;
		
    	slotStage = createSlotSelectionStage();
    	slotStage.show();
        
        slotStage.setOnCloseRequest(e -> {
        	slotStage.close();
        	slotStage = null;
        });
    }
    
    private Stage createSlotSelectionStage() {
    	
    	Stage stage = new Stage();
    	stage.setTitle("Slot Viewer");
    	
        Label selectorLabel = new Label("Select Slot:");
    	
    	slotSelector = new ComboBox<>();
    	
        for (SaveProfile p : profiles) {
            slotSelector.getItems().add(p.slot);
        }
        
        slotSelector.setOnAction(e -> {
            Integer selectedSlot = slotSelector.getSelectionModel().getSelectedItem();
            if(selectedSlot != null) {
            	updateSlotInfo(selectedSlot);
            	setCurrentFocusedSlot(selectedSlot);
            }
        });
        
        Button closeBtn = new Button("Close");
        closeBtn.setStyle("-fx-background-color: #E6E6FA");
        closeBtn.setOnAction(e -> { 
	        slotStage.hide();
        });

        Button selectBtn = new Button("Select Save File");
        selectBtn.setStyle("-fx-background-color: #E6E6FA");
        selectBtn.setOnAction(e -> {
        	try {
        		File f = openSaveFileDialog(stage);
        		if(f == null) {
        			return;
        		}
        		setSaveFile(f);
        		saveFileDir(f);
        		refreshProfiles();
        	} catch (Exception ignore) {}
        });
        
        Button helpBtn = new Button("Help");
        helpBtn.setOnAction(e -> {
        	boolean show = !INFO_LABEL.isVisible();
        	
        	INFO_LABEL.setVisible(show);
        	INFO_LABEL.setManaged(show);
        	
        	stage.sizeToScene();
        });
        
        Label space = new Label();
        
        Label saveSlot = new Label();

        CheckBox saveLastSlot = new CheckBox("Save current Slot");
        saveLastSlot.selectedProperty().addListener((obs, oldVal, newVal) -> {
        	if (newVal) {
        		writeLastSlot = true;
        		saveSlot.setText("Saving current slot on close...");
        		stage.sizeToScene();
        	} else {
        		writeLastSlot = false;
        		saveSlot.setText("");
        		stage.sizeToScene();
        	}
        });
        
        if (writeLastSlot) {
        	slotSelector.getSelectionModel().select(currentFocusedSlot);
        	saveLastSlot.setSelected(true);
        }
        
        selectorLabel.setTextFill(Color.web("#FFF0F5"));
        slotSelector.setStyle("-fx-text-fill: #FFF0F5; -fx-prompt-text-fill: #FFF0F5; -fx-background-color: #E6E6FA");
        
        nameLabel.setTextFill(Color.web("#FFF0F5"));
        levelLabel.setTextFill(Color.web("#FFF0F5"));
        timeLabel.setTextFill(Color.web("#FFF0F5"));
        deathLabel.setTextFill(Color.web("#FFF0F5"));
        
        saveLastSlot.setStyle("-fx-text-fill: #FFF0F5; -fx-prompt-text-fill: #FFF0F5;");
        saveSlot.setTextFill(Color.web("#FFF0F5"));
        
        INFO_LABEL.setTextFill(Color.web("#CDC1C5"));
        INFO_LABEL.setWrapText(true);
        INFO_LABEL.setVisible(false);
        INFO_LABEL.setManaged(false);
        
        GridPane grid = new GridPane();
        grid.setPadding(new Insets(10));
        grid.setVgap(5);
        grid.setHgap(5);
        grid.setStyle("-fx-padding: 20; -fx-font-size: 14; -fx-background-color: #000;");
        
        grid.add(selectorLabel, 0, 0);
        grid.add(slotSelector, 1, 0);
        grid.add(nameLabel, 0, 1);
        grid.add(levelLabel, 0, 2);
        grid.add(timeLabel, 0, 3);
        grid.add(deathLabel, 0, 4);
        grid.add(space, 0, 5, 2, 1);
        grid.add(saveLastSlot, 0, 6);
        grid.add(saveSlot, 0, 7);
        grid.add(INFO_LABEL, 0, 8);
        grid.add(selectBtn, 0, 9);
        grid.add(helpBtn, 1, 9);
        grid.add(closeBtn, 2, 9);

        Scene sc = new Scene(grid);
        stage.setScene(sc);
        stage.sizeToScene();
        stage.setAlwaysOnTop(true);
        return stage;
    }

    private void startDeathDetector() {
        if (deathDetector != null) return;

        deathDetector = new DeathDetector(
                () -> overlayStage != null ? getScreenFor(overlayStage) : Screen.getPrimary(),
                this::handleDetectedDeath);
        deathDetector.start();
        System.out.println("[DEATH DETECTOR] Automatic missed-death detection started.");
    }

    /**
     * Called once for every visually detected YOU DIED screen.
     * The save value is only used as a baseline here; no death is added yet.
     */
    private void handleDetectedDeath() {
        if (currentFocusedSlot < 0 || saveFile == null || !saveFile.exists()) {
            return;
        }

        SaveProfile current = profiles.stream()
                .filter(p -> p.slot == currentFocusedSlot)
                .findFirst()
                .orElse(null);

        if (current == null || current.deaths < 0) {
            return;
        }

        // Do not open a second reconciliation while the previous death is unresolved.
        if (pendingDeathSlot != -1) {
            return;
        }

        long token = ++pendingDeathToken;
        pendingDeathSlot = current.slot;
        pendingRawDeaths = current.deaths;

        System.out.println("[DEATH DETECTOR] YOU DIED detected. Slot "
                + current.slot + ", save deaths before event: " + current.deaths);

        // Show the visually detected death immediately as a temporary +1.
        // If Elden Ring later increments its own save counter, that pending +1 is
        // removed at the same time, so the displayed total never double-counts.
        Platform.runLater(() -> {
            if (currentFocusedSlot == current.slot) {
                updateSlotInfo(current.slot);
            }
        });

        if (saveFileWatcher == null || saveFileWatcher.isShutdown()) {
            return;
        }

        // Check a few times. Normal deaths usually resolve as soon as the save updates.
        saveFileWatcher.schedule(() -> reconcileDetectedDeath(token, false), 4, TimeUnit.SECONDS);
        saveFileWatcher.schedule(() -> reconcileDetectedDeath(token, false), 8, TimeUnit.SECONDS);
        saveFileWatcher.schedule(() -> reconcileDetectedDeath(token, true), FINAL_DEATH_RECONCILE_SECONDS, TimeUnit.SECONDS);
    }

    private void reconcileDetectedDeath(long token, boolean finalCheck) {
        if (token != pendingDeathToken || pendingDeathSlot < 0) {
            return;
        }

        final int slot = pendingDeathSlot;
        final int rawDeathsBefore = pendingRawDeaths;

        try {
            Parser parser = new Parser();
            List<SaveProfile> freshProfiles = parser.extractProfiles(saveFile);
            SaveProfile fresh = freshProfiles.stream()
                    .filter(p -> p.slot == slot)
                    .findFirst()
                    .orElse(null);

            if (fresh == null || fresh.deaths < 0) {
                return;
            }

            if (fresh.deaths > rawDeathsBefore) {
                System.out.println("[DEATH DETECTOR] Death already counted by Elden Ring save: "
                        + rawDeathsBefore + " -> " + fresh.deaths);
                clearPendingDeath(token);
                return;
            }

            if (!finalCheck) {
                return;
            }

            // Some death types (notably the NPC-invader case we are handling) do
            // not reliably increment Elden Ring's regular death counter and may not
            // cause a detectable save-file write at all. Once the reconciliation
            // window expires with the raw counter unchanged, the visual YOU DIED
            // event becomes the fallback authority.
            fallbackDeaths.merge(slot, 1, Integer::sum);
            saveFallbackDeaths();

            System.out.println("[DEATH DETECTOR] Save counter did not increase. Added automatic fallback +1 for slot "
                    + slot + ". Fallback deaths now: " + fallbackDeaths.getOrDefault(slot, 0));

            clearPendingDeath(token);

            Platform.runLater(() -> {
                if (currentFocusedSlot == slot) {
                    updateSlotInfo(slot);
                }
            });
        } catch (Exception ex) {
            System.err.println("[DEATH DETECTOR] Could not reconcile detected death: " + ex.getMessage());
            if (finalCheck) {
                clearPendingDeath(token);
            }
        }
    }

    /**
     * If the regular file watcher sees the normal death counter advance, resolve a
     * pending visual death immediately instead of waiting for the delayed checks.
     */
    private void reconcilePendingDeathFromSave(List<SaveProfile> updatedProfiles) {
        long token = pendingDeathToken;
        int slot = pendingDeathSlot;
        int rawDeathsBefore = pendingRawDeaths;

        if (slot < 0 || rawDeathsBefore < 0) return;

        SaveProfile updated = updatedProfiles.stream()
                .filter(p -> p.slot == slot)
                .findFirst()
                .orElse(null);

        if (updated != null && updated.deaths > rawDeathsBefore) {
            System.out.println("[DEATH DETECTOR] File watcher confirmed normal save death: "
                    + rawDeathsBefore + " -> " + updated.deaths);
            clearPendingDeath(token);
        }
    }

    private void clearPendingDeath(long token) {
        if (token != pendingDeathToken) return;
        pendingDeathSlot = -1;
        pendingRawDeaths = -1;
    }

    private void cancelPendingDeath() {
        pendingDeathToken++;
        pendingDeathSlot = -1;
        pendingRawDeaths = -1;
    }

    private int getDisplayedDeaths(SaveProfile profile) {
        if (profile == null || profile.deaths < 0) return -1;

        int pendingVisualDeath = (pendingDeathSlot == profile.slot) ? 1 : 0;
        return profile.deaths
                + fallbackDeaths.getOrDefault(profile.slot, 0)
                + pendingVisualDeath;
    }

    private File getFallbackDeathsFile() {
        if (saveFile == null) return null;

        String normalizedPath = saveFile.getAbsolutePath().toLowerCase(Locale.ROOT);
        String saveId = Integer.toHexString(normalizedPath.hashCode());
        return new File(CONFIG_DIR, "fallback_deaths_" + saveId + ".conf");
    }

    private void loadFallbackDeaths() {
        fallbackDeaths.clear();
        File file = getFallbackDeathsFile();
        if (file == null || !file.exists()) return;

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("=", 2);
                if (parts.length != 2 || !parts[0].startsWith("slot")) continue;

                int slot = Integer.parseInt(parts[0].substring(4).trim());
                int deaths = Integer.parseInt(parts[1].trim());
                if (slot >= 0 && deaths > 0) {
                    fallbackDeaths.put(slot, deaths);
                }
            }
        } catch (IOException | NumberFormatException ex) {
            System.err.println("[DEATH DETECTOR] Could not load fallback deaths: " + ex.getMessage());
        }
    }

    private void saveFallbackDeaths() {
        if (!CONFIG_DIR.exists()) CONFIG_DIR.mkdirs();

        File file = getFallbackDeathsFile();
        if (file == null) return;

        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file))) {
            fallbackDeaths.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        try {
                            bw.write("slot" + entry.getKey() + "=" + entry.getValue());
                            bw.newLine();
                        } catch (IOException ex) {
                            throw new RuntimeException(ex);
                        }
                    });
        } catch (IOException | RuntimeException ex) {
            System.err.println("[DEATH DETECTOR] Could not save fallback deaths: " + ex.getMessage());
        }
    }

    private void refreshProfiles() {
        try {
            if (saveFile == null || !saveFile.exists()) {
                Platform.runLater(() -> {
                    focusedSlotLabel.setText("No Slot selected");
                    deathCountLabel.setText("Deaths: -");
                    nameLabel.setText("Name: \t\t-");
                    levelLabel.setText("Level: \t\t-");
                    timeLabel.setText("Playtime: \t\t-");
                    deathLabel.setText("Deaths: \t\t-");
                });
                return;
            }

            Parser p = new Parser();
            List<SaveProfile> ps = p.extractProfiles(saveFile);
            this.profiles = ps;

            Platform.runLater(() -> {
                if (isSlotUIReady()) {
                    slotSelector.getItems().setAll(ps.stream().map(s -> s.slot).toList());
                }

                SaveProfile toFocus = null;
                if (currentFocusedSlot >= 0) {
                    toFocus = ps.stream().filter(s -> s.slot == currentFocusedSlot).findFirst().orElse(null);
                }
                if (toFocus == null && !ps.isEmpty()) {
                    toFocus = ps.get(0);
                }

                if (toFocus == null) {
                    focusedSlotLabel.setText("No Slot selected");
                    deathCountLabel.setText("Deaths: -");
                    nameLabel.setText("Name: \t\t-");
                    levelLabel.setText("Level: \t\t-");
                    timeLabel.setText("Playtime: \t\t-");
                    deathLabel.setText("Deaths: \t\t-");
                    setCurrentFocusedSlot(-1);
                } else {
                    updateSlotInfo(toFocus.slot);
                    setCurrentFocusedSlot(toFocus.slot);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


	private void updateSlotInfo(int slot) {
        SaveProfile profile = profiles.stream().filter(p -> p.slot == slot).findFirst().orElse(null);
        if (profile != null) {
            nameLabel.setText("Name: \t\t" + profile.name);
            levelLabel.setText("Level: \t\t" + profile.level);
            timeLabel.setText("Playtime: \t\t" + profile.formatTime());
            int displayedDeaths = getDisplayedDeaths(profile);
            deathLabel.setText("Deaths: \t\t" + (displayedDeaths >= 0 ? displayedDeaths : "-"));

            updateOverlayLabels(slot, profile);
        }
    }
    
    private void updateOverlayLabels(int slot, SaveProfile profile) {
        int displayedDeaths = getDisplayedDeaths(profile);

        if (displayedDeaths >= 0) {
            deathCountLabel.setText(String.valueOf(displayedDeaths));

            if (lastOverlayDeaths >= 0 && displayedDeaths > lastOverlayDeaths) {
                animateDeathCounter();
            }

            lastOverlayDeaths = displayedDeaths;
        } else {
            deathCountLabel.setText("-");
        }
    }

    private void animateDeathCounter() {
        ScaleTransition scaleAnimation = new ScaleTransition(Duration.millis(160), deathCountLabel);
        scaleAnimation.setFromX(1.0);
        scaleAnimation.setFromY(1.0);
        scaleAnimation.setToX(1.35);
        scaleAnimation.setToY(1.35);
        scaleAnimation.setAutoReverse(true);
        scaleAnimation.setCycleCount(2);

        FadeTransition fadeAnimation = new FadeTransition(Duration.millis(320), deathCountLabel);
        fadeAnimation.setFromValue(0.55);
        fadeAnimation.setToValue(1.0);

        scaleAnimation.play();
        fadeAnimation.play();
    }
    
    private double pressOffsetX, pressOffsetY;
    
    private void startOverlay() {
        overlayStage = new Stage();
	    
        
        root = new VBox(deathCountLabel);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(12, 24, 12, 24));
        root.setBackground(Background.EMPTY);
		root.setMouseTransparent(true);
		root.setPickOnBounds(false);
		
		Scene scene = new Scene(root);
		scene.setFill(Color.TRANSPARENT);
		
		scene.setOnMouseMoved(e -> {if(e.isAltDown()){scene.setCursor(Cursor.OPEN_HAND);}else{scene.setCursor(Cursor.DEFAULT);};});
		scene.setOnMousePressed(e -> {
			if(!e.isAltDown()) return;
			pressOffsetX = e.getSceneX();
			pressOffsetY = e.getSceneY();
			scene.setCursor(Cursor.CLOSED_HAND);
		});
		scene.setOnMouseDragged(e -> {
			if(!e.isAltDown()) return;
		    overlayStage.setX((e.getScreenX() - pressOffsetX));
		    overlayStage.setY((e.getScreenY() - pressOffsetY));
		});
		scene.setOnMouseReleased(e -> {
			scene.setCursor(Cursor.DEFAULT);
			clampToVisible(overlayStage);
			saveOverlayConfig();
		});
		scene.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
			if(e.getCode() == KeyCode.ALT) root.setMouseTransparent(false);
		});
		scene.addEventFilter(KeyEvent.KEY_RELEASED, e -> {
			if(e.getCode() == KeyCode.ALT) root.setMouseTransparent(true);
		});
		
        overlayStage.initStyle(StageStyle.TRANSPARENT);
		overlayStage.setAlwaysOnTop(true);
		overlayStage.setScene(scene);
		overlayStage.setMinWidth(200);
		overlayStage.setMinHeight(75);
		overlayStage.setMaxWidth(1900);
		overlayStage.setMaxHeight(1100);
        overlayStage.show();
        
        Screen s = getScreenFor(overlayStage);
        Rectangle2D vb = s.getVisualBounds();
        overlayStage.setX(vb.getMaxX() - 210);
        overlayStage.setY(vb.getMinY() + 10);
        
        wireAutoClamp(overlayStage);
        clampToVisible(overlayStage);
        
        overlayStage.toFront();
               
        initBaseSize();
        loadOverlayConfig();
        
      //Finish-loading--------------------------\\
        Platform.runLater(() -> {
        	root.setPrefWidth(baseWidth * scale);
		    root.setPrefHeight(baseHeight * scale);
		    
		    DropShadow glow = new DropShadow();
		    glow.setOffsetX(0);
		    glow.setOffsetY(0);
		    glow.setRadius(12);
		    glow.setColor(Color.rgb(215, 180, 106, 0.85));
		    
		    deathCountLabel.setFont(Font.font("Prince Valiant", FontWeight.BOLD, BASE_FONT_SIZE_BIG * scale));
		    deathCountLabel.setTextFill(Color.web("#D7B46A"));
		    deathCountLabel.setEffect(glow);
		    focusedSlotLabel.setFont(Font.font("Prince Valiant", BASE_FONT_SIZE_SMALL * scale));
		    focusedSlotLabel.setTextFill(Color.web("#B0BEC5"));
		    
		    overlayStage.setWidth(baseWidth * scale);
		    overlayStage.setHeight(baseHeight * scale);
		    overlayStage.setOpacity(overlayStage.getOpacity());
		    
		    clampToVisible(overlayStage);
        });
        
        overlayStage.setOnCloseRequest(e -> {
        	overlayStage.close();
        });
    }
    
    public static Screen getScreenFor(Stage stage) {
    	Rectangle2D win = new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
    	var list = Screen.getScreensForRectangle(win);
    	return list.isEmpty() ? Screen.getPrimary() : list.get(0);
    }
    
    public static void clampToVisible(Stage stage) {
    	Screen s = getScreenFor(stage);
		Rectangle2D b = s.getVisualBounds();
		double nx = Math.max(b.getMinX(), Math.min(stage.getX(), b.getMaxX() - stage.getWidth()));
		double ny = Math.max(b.getMinY(), Math.min(stage.getY(), b.getMaxY() - stage.getHeight()));
		stage.setX(nx);
		stage.setY(ny);
		return;
	}
    
    public static void centerOnCurrentScreen(Stage stage) {
    	Screen s = getScreenFor(stage);
        Rectangle2D vb = s.getVisualBounds();
        double x = vb.getMinX() + (vb.getWidth()  - stage.getWidth())  / 2.0;
        double y = vb.getMinY() + (vb.getHeight() - stage.getHeight()) / 2.0;
        stage.setX(x);
        stage.setY(y);
    }
    
    public static void moveToMouseScreen(Stage stage) {
    	Robot r = new Robot();
    	double mx = r.getMouseX();
    	double my = r.getMouseY();
    	
    	Screen target = Screen.getScreensForRectangle(new Rectangle2D(mx, my, 1, 1)).stream().findFirst().orElse(Screen.getPrimary());
    	Rectangle2D vb = target.getVisualBounds();
    	stage.setX(vb.getMinX() + 20);
    	stage.setY(vb.getMinY() + 20);
    }

    public static void wireAutoClamp(Stage stage) {
    	Runnable clamp = () -> clampToVisible(stage);
    	stage.xProperty().addListener((obs, o, n) -> clamp.run());
    	stage.yProperty().addListener((obs, o, n) -> clamp.run());
    	stage.widthProperty().addListener((obs, o, n) -> clamp.run());
    	stage.heightProperty().addListener((obs, o, n) -> clamp.run());
    }
    
	//Helpers Overlay - Style/Config-----------------------------------------------------------------------\\
    private void initBaseSize() {
    	baseWidth = root.getWidth() > 0 ? root.getWidth() : 200;
    	baseHeight = root.getHeight() > 0 ? root.getHeight() : 75;
    }

    //Optional: Fade-Out-Timer-----------------------------------------------\\
//    private void startFadeOutTimer(Label label, int secondsDelay) {
//    	FadeTransition fade = new FadeTransition(Duration.seconds(2), label);
//		fade.setFromValue(1.0);
//		fade.setToValue(0.0);
//		fade.setDelay(Duration.seconds(secondsDelay));
//		fade.setOnFinished(e -> {
//			Platform.runLater(() -> {
//				root.getChildren().remove(label);
//			});
//		});
//		fade.play();
//    }
    
    private boolean loadLastSlot() {
    	if (!CONFIG_DIR.exists()) CONFIG_DIR.mkdirs();

	    if (!LAST_SLOT_DIR.exists()) {
	        return false;
	    }

	    try (BufferedReader br = new BufferedReader(new FileReader(LAST_SLOT_DIR))) {
	        String line;
	        while ((line = br.readLine()) != null) {
	            String[] parts = line.split("=");
	            if (parts.length != 2) continue;
	            String key = parts[0].trim();
	            String value = parts[1].trim();

	            if (key.equals("slot")) {
	            	setCurrentFocusedSlot(Integer.parseInt(value)); 
	            	return true;
	            };
	        }
	    } catch (IOException | NumberFormatException e) {
	        System.out.println("Error while loading slot configuration: " + e.getMessage());
	        e.printStackTrace();
	    }
	    return false;
    }
    
    private void saveLastSlot(int slot) {
    	if (!CONFIG_DIR.exists()) CONFIG_DIR.mkdirs();

		try (BufferedWriter bw = new BufferedWriter(new FileWriter(LAST_SLOT_DIR))) {
			bw.write("slot=" + slot + "\n"); 
			System.out.println("Successfully written last Slot to config");
        	
		} catch (IOException e) {
			System.out.println("Error while saving overlay configuration: " + e.getMessage());
		}
    }
    
    
    private void loadOverlayConfig() {
	    if (!CONFIG_DIR.exists()) CONFIG_DIR.mkdirs();

	    if (!OVERLAY_CONF_FILE.exists()) {
	        return;
	    }

	    try (BufferedReader br = new BufferedReader(new FileReader(OVERLAY_CONF_FILE))) {
	        String line;
	        while ((line = br.readLine()) != null) {
	            String[] parts = line.split("=");
	            if (parts.length != 2) continue;
	            String key = parts[0].trim();
	            String value = parts[1].trim();

	            switch (key) {
	                case "posX" -> overlayStage.setX(Double.parseDouble(value));
	                case "posY" -> overlayStage.setY(Double.parseDouble(value));
	                case "width" -> baseWidth = Double.parseDouble(value);
	                case "height" -> baseHeight = Double.parseDouble(value);
	                case "opacity" -> overlayStage.setOpacity(Double.parseDouble(value));
	                case "scale" -> scale = Double.parseDouble(value);
	                default -> {}
	            }
	        }
	    } catch (IOException | NumberFormatException e) {
	        System.out.println("Error while loading overlay configuration: " + e.getMessage());
	        e.printStackTrace();
	    }
	}
    
    private void saveOverlayConfig() {
		if (!CONFIG_DIR.exists()) CONFIG_DIR.mkdirs();

		try (BufferedWriter bw = new BufferedWriter(new FileWriter(OVERLAY_CONF_FILE))) {
			bw.write("posX=" + overlayStage.getX() + "\n");
			bw.write("posY=" + overlayStage.getY() + "\n");

			bw.write("width=" + baseWidth + "\n");
			bw.write("height=" + baseHeight + "\n");
        	bw.write("opacity=" + overlayStage.getOpacity() + "\n");
        	bw.write("scale=" + scale + "\n");
        	
		} catch (IOException e) {
			System.out.println("Error while saving overlay configuration: " + e.getMessage());
		}
	}
    
    private void saveFileDir(File saveFile) {
    	if(!CONFIG_DIR.exists()) CONFIG_DIR.mkdirs();
    	
    	try (BufferedWriter bw = new BufferedWriter(new FileWriter(SAVE_FILE_DIR))) {
    		if(saveFile != null && saveFile.exists()) {
    			bw.write(saveFile.getAbsolutePath());    			
    		} else {
    			return;
    		}
    	} catch (IOException e) {
    		e.printStackTrace();
    	}
    }
    
    private File loadSaveFileDir() {
    	if (!CONFIG_DIR.exists()) CONFIG_DIR.mkdirs();
	    if (!SAVE_FILE_DIR.exists()) return null;
	    
	    File saveFile = null;
	    
	    try (BufferedReader br = new BufferedReader(new FileReader(SAVE_FILE_DIR))) {
	    	String line = br.readLine();
	    	if(line == null || line.isBlank()) return null;	    	
	    	saveFile = new File(line.trim());
	    	return(saveFile.exists() ? saveFile : null);
	    } catch (IOException e) {
	    	e.printStackTrace();
	    }
	    return saveFile;
    }  
    
    //Helpers Initialization----------------------------------------------------------------------------------\\    
    private void startFileWatcher(Parser parser) {
        saveFileWatcher = Executors.newSingleThreadScheduledExecutor();
        saveFileWatcher.scheduleAtFixedRate(() -> {
            try {
                File f = this.saveFile;
                if (f == null || !f.exists()) return;

                FileTime currentModTime;
                try {
                    currentModTime = Files.getLastModifiedTime(f.toPath());
                } catch (IOException io) {
                    return;
                }

                if (lastModifiedTime == null || currentModTime.toMillis() > lastModifiedTime.toMillis()) {
                    lastModifiedTime = currentModTime;

                    List<SaveProfile> updatedProfiles = parser.extractProfiles(f);
                    reconcilePendingDeathFromSave(updatedProfiles);

                    if (updatedProfiles.size() != profiles.size()) {
                        profiles = updatedProfiles;
                    } else {
                        for (int i = 0; i < profiles.size(); i++) {
                            profiles.get(i).deaths = updatedProfiles.get(i).deaths;
                            profiles.get(i).time   = updatedProfiles.get(i).time;
                            profiles.get(i).level  = updatedProfiles.get(i).level;
                        }
                    }

                    if (currentFocusedSlot != -1) {
                        Platform.runLater(() -> updateSlotInfo(currentFocusedSlot));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 2, TimeUnit.SECONDS);
    }

    private File openSaveFileDialog(Window ownerWindow) {
        FileChooser fC = new FileChooser();
        fC.setTitle("Choose Elden Ring Save-File");
        if(DEFAULT_ER_DIR != null && DEFAULT_ER_DIR.exists() && DEFAULT_ER_DIR.isDirectory()) fC.setInitialDirectory(DEFAULT_ER_DIR);
        fC.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Elden Ring Save Files (*.sl2;*.co2;*.mod)", "*.sl2", "*.co2", ".mod"));
        fC.getExtensionFilters().add(
        		new FileChooser.ExtensionFilter("All Files", "*.*"));
        return (ownerWindow != null) ? fC.showOpenDialog(ownerWindow) : fC.showOpenDialog(null);
    }
    
    private boolean isSlotUIReady() {
    	return this.slotSelector != null;
    }
    
    //Getters and Setters------------------------------------------------------------------------------------\\
    public List<SaveProfile> getProfiles() {
        return profiles;
    }

    public void setProfiles(List<SaveProfile> profiles) {
        this.profiles = profiles;
    }

    public int getCurrentFocusedSlot() {
        return currentFocusedSlot;
    }

    public void setCurrentFocusedSlot(int currentFocusedSlot) {
        this.currentFocusedSlot = currentFocusedSlot;
    }

    public File getSaveFile() {
        return saveFile;
    }

    public void setSaveFile(File saveFile) {
        this.saveFile = saveFile;
        this.lastModifiedTime = null;
        cancelPendingDeath();
        loadFallbackDeaths();

        if (saveFile != null && saveFile.exists()
                && (saveFileWatcher == null || saveFileWatcher.isShutdown())) {
            startFileWatcher(new Parser());
        }
    }
}
