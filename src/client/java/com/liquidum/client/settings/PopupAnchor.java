package com.liquidum.client.settings;

// Data source for the dropdown popup, rows expose labels plus value rect
public interface PopupAnchor {
	java.util.List<String> popupLabels();
	int popupSelected();
	void popupPick(int idx);
	int[] popupValueRect();
}
