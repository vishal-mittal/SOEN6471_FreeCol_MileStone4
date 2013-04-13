package net.sf.freecol.server.control;

import java.util.ArrayList;

import net.sf.freecol.server.control.ChangeSet.Change;

public class ChangeSetData {
	private ArrayList<Change> changes;

	public ChangeSetData() {
	}

	public ArrayList<Change> getChanges() {
		return changes;
	}

	public void setChanges(ArrayList<Change> changes) {
		this.changes = changes;
	}
}