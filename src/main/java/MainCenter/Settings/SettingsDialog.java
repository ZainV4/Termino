package MainCenter.Settings;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.function.Consumer;

public class SettingsDialog {
    private final Stage owner;
    private final SettingsService service;

    public SettingsDialog(Stage owner, SettingsService service) {
        this.owner = owner; this.service = service;
    }

    public void showAndApply(Settings current, Consumer<Settings> onApply) {
        Settings working = new Settings();
        // copy
        working.bg = current.bg;
        working.fg = current.fg;
        working.border = current.border;
        working.prompt = current.prompt;
        working.fontSize = current.fontSize;
        working.showTimestamps = current.showTimestamps;
        working.autoScroll = current.autoScroll;
        working.alwaysOnTop = current.alwaysOnTop;
        working.beepOnError = current.beepOnError;

        // UI
        ColorPicker cpBg = new ColorPicker(Color.web(working.bg));
        ColorPicker cpFg = new ColorPicker(Color.web(working.fg));
        TextField tfPrompt = new TextField(working.prompt);
        Slider slFont = new Slider(10, 24, working.fontSize); slFont.setShowTickMarks(true); slFont.setShowTickLabels(true);
        CheckBox cbTs = new CheckBox("Show timestamps"); cbTs.setSelected(working.showTimestamps);
        CheckBox cbScroll = new CheckBox("Auto-scroll"); cbScroll.setSelected(working.autoScroll);
        CheckBox cbTop = new CheckBox("Always on top"); cbTop.setSelected(working.alwaysOnTop);
        CheckBox cbBeep = new CheckBox("Beep on error"); cbBeep.setSelected(working.beepOnError);

        Button btnCancel = new Button("Cancel");
        Button btnSave = new Button("Save");

        GridPane gp = new GridPane();
        gp.setPadding(new Insets(12));
        gp.setHgap(10); gp.setVgap(8);
        gp.addRow(0, new Label("Background"), cpBg);
        gp.addRow(1, new Label("Text (green)"), cpFg);
        gp.addRow(2, new Label("Prompt"), tfPrompt);
        gp.addRow(3, new Label("Font size"), slFont);
        gp.addRow(4, cbTs, cbScroll);
        gp.addRow(5, cbTop, cbBeep);
        gp.add(btnCancel, 0, 6);
        gp.add(btnSave, 1, 6);

        Stage dlg = new Stage(StageStyle.UTILITY);
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Settings");
        dlg.setScene(new Scene(gp, 360, 280));

        btnCancel.setOnAction(e -> dlg.close());
        btnSave.setOnAction(e -> {
            working.bg = toHex(cpBg.getValue());
            working.fg = toHex(cpFg.getValue());
            working.prompt = tfPrompt.getText().isBlank() ? current.prompt : tfPrompt.getText();
            working.fontSize = (int) Math.round(slFont.getValue());
            working.showTimestamps = cbTs.isSelected();
            working.autoScroll = cbScroll.isSelected();
            working.alwaysOnTop = cbTop.isSelected();
            working.beepOnError = cbBeep.isSelected();
            try { service.save(working); } catch (Exception ignored) {}
            onApply.accept(working);
            dlg.close();
        });

        dlg.showAndWait();
    }

    private static String toHex(Color c) {
        return String.format("#%02x%02x%02x",
                (int)Math.round(c.getRed()*255),
                (int)Math.round(c.getGreen()*255),
                (int)Math.round(c.getBlue()*255));
    }
}
