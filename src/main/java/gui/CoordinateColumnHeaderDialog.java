package gui;

import table.XYHeaders;

import javax.swing.*;
import javax.swing.border.EmptyBorder;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Dialog to select a coordinate columns in a result table
 *
 * @author Felix Meyenhofer
 */
public class CoordinateColumnHeaderDialog extends JPanel implements ActionListener, XYHeaders {

    private final String TABLE_SELECTOR_NAME = "table";
    private final String X_SELECTOR_NAME = "x coord. col.";
    private final String Y_SELECTOR_NAME = "y coord. col.";

    private final String OK_BUTTON_NAME = "OK";
    private final String CANCEL_BUTTON_NAME = "Cancel";

    private HashMap<String, String[]> tableInfo;

    private JComboBox<String> tableSelector;
    private JComboBox<String> xColSelector;
    private JComboBox<String> yColSelector;
    private final JButton okButton;


    private CoordinateColumnHeaderDialog(HashMap<String, String[]> tinfo) {
        super();
        this.tableInfo = tinfo;

        this.setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        this.setBorder(new EmptyBorder(10, 10, 10, 10));

        tableSelector = new JComboBox<>(tableInfo.keySet().toArray(new String[0]));
        tableSelector.setName(TABLE_SELECTOR_NAME);
        tableSelector.addActionListener(this);
        if (tableInfo.size() > 1) {
            JPanel panel1 = new JPanel(new FlowLayout());
            panel1.add(new JLabel(TABLE_SELECTOR_NAME));
            panel1.add(tableSelector);
            this.add(panel1);
        }

        xColSelector = new JComboBox<>();
        xColSelector.setName(X_SELECTOR_NAME);
        xColSelector.addActionListener(this);
        JPanel panel2 = new JPanel(new FlowLayout());
        panel2.add(new JLabel(X_SELECTOR_NAME));
        panel2.add(xColSelector);
        this.add(panel2);

        yColSelector = new JComboBox<>();
        yColSelector.setName(Y_SELECTOR_NAME);
        yColSelector.addActionListener(this);
        JPanel panel3 = new JPanel(new FlowLayout());
        panel3.add(new JLabel(Y_SELECTOR_NAME));
        panel3.add(yColSelector);
        this.add(panel3);

        okButton = new JButton(OK_BUTTON_NAME);
        okButton.setName(OK_BUTTON_NAME);
        okButton.addActionListener(this);
        JButton cancelButton = new JButton(CANCEL_BUTTON_NAME);
        cancelButton.setName(CANCEL_BUTTON_NAME);
        cancelButton.addActionListener(this);
        JPanel panel4 = new JPanel();
        panel4.setBorder(new EmptyBorder(10, 0, 0, 0));
        panel4.add(cancelButton);
        panel4.add(okButton);
        this.add(panel4);
    }

    public static CoordinateColumnHeaderDialog createAndShow(List<String> headers) {
        HashMap<String, String[]> tableInfo = new HashMap<>();
        tableInfo.put("Result Table", headers.toArray(new String[0]));
        return createAndShow(tableInfo);
    }

    public static CoordinateColumnHeaderDialog createAndShow(HashMap<String, String[]> tableInfo) {
        Dimension dim = java.awt.Toolkit.getDefaultToolkit().getScreenSize();

        CoordinateColumnHeaderDialog dialog = new CoordinateColumnHeaderDialog(tableInfo);
        dialog.update();

        JDialog frame = new JDialog();
        frame.setTitle("Select the Coordinate Columns");
        frame.add(dialog);
        frame.setModal(true);
        frame.setSize(new Dimension(300,260));
        frame.setLocation(dim.width / 2 - 150, dim.height / 2 - 130);
        frame.setLocationByPlatform(true);
        frame.getRootPane().setDefaultButton(dialog.okButton);
        dialog.okButton.requestFocus();
        frame.setVisible(true);

        return dialog;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        update(((Component) e.getSource()).getName());
    }

    private void update() {
        update(this.TABLE_SELECTOR_NAME);
    }

    private void update(String control) {
        xColSelector.removeActionListener(this);
        yColSelector.removeActionListener(this);

        switch (control) {

            case TABLE_SELECTOR_NAME:
                xColSelector.setSelectedItem(null);
                yColSelector.setSelectedItem(null);
                updateColumnChoices(xColSelector, yColSelector);
                break;

            case Y_SELECTOR_NAME:
                updateColumnChoices(yColSelector, xColSelector);
                break;

            case X_SELECTOR_NAME:
                updateColumnChoices(xColSelector, yColSelector);
                break;

            case CANCEL_BUTTON_NAME:
                xColSelector.setSelectedItem(null);
                yColSelector.setSelectedItem(null);
                // continue to next case to close the dialog

            case OK_BUTTON_NAME:
//                if (getXColumn().equals(getYColumn())) {
//                    JDialog parent = (JDialog) SwingUtilities.getWindowAncestor(this);
//                    JOptionPane.showMessageDialog(parent, "X and y header have to be different.");
//                    break;
//                }

                JDialog dialog = (JDialog) SwingUtilities.getWindowAncestor(this);
                dialog.dispose();
                break;
        }

        xColSelector.addActionListener(this);
        yColSelector.addActionListener(this);
    }


    private void updateColumnChoices(JComboBox<String> trigger, JComboBox<String> target) {
        String tableName = (String) tableSelector.getSelectedItem();
        String choice1 = (String) trigger.getSelectedItem();
        String choice2 = (String) target.getSelectedItem();

        trigger.removeAllItems();
        target.removeAllItems();
        for (String item : tableInfo.get(tableName)) {
            trigger.addItem(item);
            target.addItem(item);
        }

        trigger.setSelectedItem(choice1);
        if (trigger.getSelectedIndex() < 0) {
            trigger.setSelectedIndex(0);
            choice1 = (String) trigger.getSelectedItem();
        }
        target.removeItem(choice1);

        target.setSelectedItem(choice2);
        if (target.getSelectedIndex() < 0) {
            target.setSelectedIndex(0);
            choice2 = (String) target.getSelectedItem();
        }
        trigger.removeItem(choice2);

        this.repaint();
    }

    public String getXColumn() {
        return (String) xColSelector.getSelectedItem();
    }

    public String getYColumn() {
        return (String) yColSelector.getSelectedItem();
    }

    public List<String> getColumns() {
        List<String> cols = new ArrayList<>(2);
        cols.add(getXColumn());
        cols.add(getYColumn());

        return cols;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                HashMap<String, String[]> tables = new HashMap<>();
                tables.put("table 1", new String[]{"X", "Y", "something", "else"});
                tables.put("table 2", new String[]{"first", "second", "XM", "centroid", "YM", "last"});

                CoordinateColumnHeaderDialog dialog = createAndShow(tables);
                System.out.println(dialog.getXColumn());
                System.out.println(dialog.getYColumn());
                System.exit(0);
            }
        });
    }
}
