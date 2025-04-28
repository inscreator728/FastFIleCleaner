import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.List;
import java.util.ArrayList;
import java.util.EnumSet;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

public class FastFileDeleter extends JFrame {
    private JTextField pathField;
    private JButton deleteButton;
    private JProgressBar progressBar;
    private DefaultListModel<String> historyModel;
    private JList<String> historyList;

    public FastFileDeleter() {
        setTitle("Fast File Deleter");
        setSize(700, 400);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        initUI();
    }

    private void initUI() {
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Top: Path selection
        JPanel topPanel = new JPanel(new BorderLayout(5, 5));
        pathField = new JTextField();
        JButton browseButton = new JButton("Browse...");
        browseButton.addActionListener(e -> browseAction());
        topPanel.add(pathField, BorderLayout.CENTER);
        topPanel.add(browseButton, BorderLayout.EAST);
        mainPanel.add(topPanel, BorderLayout.NORTH);

        // Center: Delete button and history list
        JPanel centerPanel = new JPanel(new BorderLayout(5, 5));
        deleteButton = new JButton("Delete Permanently");
        deleteButton.setForeground(Color.RED);
        deleteButton.addActionListener(e -> deleteAction());
        centerPanel.add(deleteButton, BorderLayout.NORTH);

        historyModel = new DefaultListModel<>();
        historyList = new JList<>(historyModel);
        JScrollPane historyScroll = new JScrollPane(historyList);
        historyScroll.setBorder(BorderFactory.createTitledBorder("Action History"));
        centerPanel.add(historyScroll, BorderLayout.CENTER);
        mainPanel.add(centerPanel, BorderLayout.CENTER);

        // Bottom: Progress bar
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        mainPanel.add(progressBar, BorderLayout.SOUTH);

        add(mainPanel);
    }

    private void browseAction() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            pathField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void deleteAction() {
        String path = pathField.getText().trim();
        if (path.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select a file or folder first!");
            return;
        }
        Path filePath = Paths.get(path);
        if (!Files.exists(filePath)) {
            JOptionPane.showMessageDialog(this, "The selected path does not exist!");
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to PERMANENTLY delete this?\n" + path,
                "Confirm Deletion",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION)
            return;

        // Start background task
        DeleteTask task = new DeleteTask(filePath);
        task.addPropertyChangeListener(new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                if ("progress".equals(evt.getPropertyName())) {
                    progressBar.setValue((Integer) evt.getNewValue());
                }
            }
        });
        deleteButton.setEnabled(false);
        historyModel.clear();
        task.execute();
    }

    private class DeleteTask extends SwingWorker<Void, String> {
        private Path target;
        private List<Path> filesToDelete = new ArrayList<>();
        private List<String> deletedDetails = new ArrayList<>();

        public DeleteTask(Path target) {
            this.target = target;
        }

        @Override
        protected Void doInBackground() throws Exception {
            gatherPaths(target);
            int total = filesToDelete.size();
            int count = 0;

            for (Path p : filesToDelete) {
                publish("Deleting: " + p);
                try {
                    // Attempt to clear DOS attributes & ACL before delete
                    clearAttributesAndPermissions(p);
                    // Delete file or directory
                    Files.deleteIfExists(p);
                    publish("Deleted: " + p);
                    BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                    deletedDetails.add(String.format(
                            "Name: %s\nPath: %s\nSize: %d bytes\n",
                            p.getFileName(), p.toAbsolutePath(), attrs.size()));
                } catch (IOException ex) {
                    // Fallback to system command
                    try {
                        Process proc = new ProcessBuilder("cmd", "/c", "del", "/f", "/q", p.toString()).start();
                        proc.waitFor();
                        publish("Force-deleted via cmd: " + p);
                        deletedDetails.add("Force-deleted via cmd: " + p);
                    } catch (Exception cmdEx) {
                        publish("Error deleting " + p + ": " + ex.getMessage());
                    }
                }
                count++;
                setProgress((int) (count * 100.0 / total));
            }
            return null;
        }

        private void gatherPaths(Path start) throws IOException {
            if (Files.isDirectory(start)) {
                Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        filesToDelete.add(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                        filesToDelete.add(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                filesToDelete.add(start);
            }
        }

        private void clearAttributesAndPermissions(Path p) {
            try {
                DosFileAttributeView dosView = Files.getFileAttributeView(p, DosFileAttributeView.class);
                if (dosView != null) {
                    dosView.setReadOnly(false);
                    dosView.setHidden(false);
                    dosView.setSystem(false);
                }
            } catch (IOException ignored) {
            }

            try {
                AclFileAttributeView aclView = Files.getFileAttributeView(p, AclFileAttributeView.class);
                if (aclView != null) {
                    UserPrincipalLookupService lookup = p.getFileSystem().getUserPrincipalLookupService();
                    UserPrincipal everyone = lookup.lookupPrincipalByName("Everyone");
                    AclEntry entry = AclEntry.newBuilder()
                            .setType(AclEntryType.ALLOW)
                            .setPrincipal(everyone)
                            .setPermissions(EnumSet.of(AclEntryPermission.DELETE, AclEntryPermission.WRITE_ACL))
                            .build();
                    List<AclEntry> acl = new ArrayList<>(aclView.getAcl());
                    acl.add(0, entry);
                    aclView.setAcl(acl);
                }
            } catch (IOException ignored) {
            }
        }

        @Override
        protected void process(List<String> chunks) {
            for (String msg : chunks) {
                historyModel.addElement(msg);
            }
        }

        @Override
        protected void done() {
            setProgress(100);
            historyModel.addElement("Deletion complete.");

            String[] options = { "OK", "Details" };
            int choice = JOptionPane.showOptionDialog(
                    FastFileDeleter.this,
                    "All operations finished.",
                    "Done",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.INFORMATION_MESSAGE,
                    null,
                    options,
                    options[0]);

            if (choice == 1) {
                JTextArea textArea = new JTextArea();
                textArea.setEditable(false);
                for (String d : deletedDetails) {
                    textArea.append(d + "\n");
                }
                JScrollPane scroll = new JScrollPane(textArea);
                scroll.setPreferredSize(new Dimension(500, 300));
                JOptionPane.showMessageDialog(
                        FastFileDeleter.this,
                        scroll,
                        "Deleted File Details",
                        JOptionPane.INFORMATION_MESSAGE);
            }

            progressBar.setValue(0);
            historyModel.clear();
            pathField.setText("");
            deleteButton.setEnabled(true);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new FastFileDeleter().setVisible(true));
    }
}
