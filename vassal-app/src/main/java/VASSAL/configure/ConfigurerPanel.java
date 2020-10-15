/*
 *
 * Copyright (c) 2020 by VASSAl Development Team
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License (LGPL) as published by the Free Software Foundation.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this library; if not, copies are available
 * at http://www.opensource.org.
 */
package VASSAL.configure;

import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * A standardised Panel to hold an individual Configurer
 */
public class ConfigurerPanel extends JPanel {

  /**
   * Create an alternate layout depending on whether or not a label is supplied for this configurer
   * New-style Configurers will always supply a blank label.
   * Legacy style Configurers will supply a text label and the column constraints must included a column for this. If a
   * label is supplied, then it will be added as a JLabel into the first column.
   * This option is supplied to provide support for custom coded Trait configurers.
   *
   * @param name The text of the supplied label
   * @param noNameColConstraints Column constraints to apply if the supplied name is null or empty
   * @param nameColConstraints Column constraints to apply if a label is supplied
   */
  public ConfigurerPanel(String name, String noNameColConstraints, String nameColConstraints) {
    super();
    setLayout(new ConfigurerLayout(name, noNameColConstraints, nameColConstraints));
    if (name != null && ! name.isEmpty()) {
      add(new JLabel(name));
    }
  }
}
