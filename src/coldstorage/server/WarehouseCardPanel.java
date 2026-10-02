package coldstorage.server;

import coldstorage.model.TemperatureData;
import coldstorage.util.DatabaseUtil;
import coldstorage.util.SensorAnomalyDetector;

import javax.swing.*;
import java.awt.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WarehouseCardPanel extends JPanel {
    
    private final Map<String, WarehouseCard> cards = new ConcurrentHashMap<>();
    private final JPanel cardsContainer;

    public WarehouseCardPanel() {
        setLayout(new BorderLayout());
        
        cardsContainer = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 15));
        cardsContainer.setBackground(UiTheme.PAGE_BG);
        
        JScrollPane scroll = new JScrollPane(cardsContainer);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
    }

    public void updateCard(TemperatureData data, boolean isOffline, boolean isMaintenance, boolean hasAnomaly) {
        String wh = data.getWarehouseName();
        WarehouseCard card = cards.get(wh);
        if (card == null) {
            card = new WarehouseCard(wh);
            cards.put(wh, card);
            cardsContainer.add(card);
            cardsContainer.revalidate();
            cardsContainer.repaint();
        }
        card.update(data, isOffline, isMaintenance, hasAnomaly);
    }

    public void removeCard(String warehouseName) {
        WarehouseCard card = cards.remove(warehouseName);
        if (card != null) {
            cardsContainer.remove(card);
            cardsContainer.revalidate();
            cardsContainer.repaint();
        }
    }
}
