/*
 * Copyright (C) 2011 Jason von Nieda <jason@vonnieda.org>
 * 
 * This file is part of OpenPnP.
 * 
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * OpenPnP is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with OpenPnP. If not, see
 * <http://www.gnu.org/licenses/>.
 * 
 * For more information about OpenPnP visit http://openpnp.org
 */

package org.openpnp.machine.reference.feeder;

import java.util.List;

import javax.swing.Action;

import org.apache.commons.io.IOUtils;
import org.opencv.core.RotatedRect;
import org.openpnp.gui.MainFrame;
import org.openpnp.gui.support.PropertySheetWizardAdapter;
import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.reference.ReferenceFeeder;
import org.openpnp.machine.reference.feeder.wizards.ReferenceLoosePartFeederConfigurationWizard;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.PropertySheetHolder;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.OpenCvUtils;
import org.openpnp.util.VisionUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.simpleframework.xml.Element;

public class ReferenceLoosePartFeeder extends ReferenceFeeder {
    @Element(required = false)
    private CvPipeline pipeline = createDefaultPipeline();

    private Location pickLocation;

    @Override
    public Location getPickLocation() throws Exception {
        return pickLocation == null ? location : pickLocation;
    }

    @Override
    public void feed(Nozzle nozzle) throws Exception {
        Camera camera = nozzle.getHead()
                              .getDefaultCamera();
        // Move to the feeder pick location
        MovableUtils.moveToLocationAtSafeZ(camera, location);
        try (CvPipeline pipeline = getPipeline()) {
            for (int i = 0; i < 3; i++) {
                pickLocation = getPickLocation(pipeline, camera, nozzle);
                camera.moveTo(pickLocation.derive(null, null, null, 0.0));
            }
            MainFrame.get()
                     .getCameraViews()
                     .getCameraView(camera)
                     .showFilteredImage(OpenCvUtils.toBufferedImage(pipeline.getWorkingImage()),
                             1000);
        }
    }

    @Override
    public boolean isPartHeightAbovePickLocation() {
        return true;
    }

    private Location getPickLocation(CvPipeline pipeline, Camera camera, Nozzle nozzle)
            throws Exception {
        // Process the pipeline to extract RotatedRect results
        pipeline.setProperty("camera", camera);
        pipeline.setProperty("nozzle", nozzle);
        pipeline.setProperty("feeder", this);
        pipeline.process();
        // Grab the results
        List<RotatedRect> results = pipeline.getExpectedResult(VisionUtils.PIPELINE_RESULTS_NAME)
                .getExpectedListModel(RotatedRect.class, 
                        new Exception("Feeder " + getName() + ": No parts found."));

        // Find the closest result
        results.sort((a, b) -> {
            Double da = VisionUtils.getPixelLocation(camera, a.center.x, a.center.y)
                                   .getLinearDistanceTo(camera.getLocation());
            Double db = VisionUtils.getPixelLocation(camera, b.center.x, b.center.y)
                                   .getLinearDistanceTo(camera.getLocation());
            return da.compareTo(db);
        });
        RotatedRect result = results.get(0);
        // Get the result's Location
        Location location = VisionUtils.getPixelLocation(camera, result.center.x, result.center.y);
        // Update the location's rotation with the result's angle
        location = location.derive(null, null, null, result.angle + this.location.getRotation());
        // Update the location with the correct Z, which is the configured Location's Z.
        double z = this.location.convertToUnits(location.getUnits()).getZ(); 
        location = location.derive(null, null, z, null);
        return location;
    }

    /**
     * Returns if the feeder can take back a part.
     * Makes the assumption, that after each feed a pick followed,
     * so the area around the pickLocation is now empty.
     */
    @Override
    public boolean canTakeBackPart() {
        if (pickLocation != null ) {  
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void takeBackPart(Nozzle nozzle) throws Exception {
        // first check if we can and want to take back this part (should be always be checked before calling, but to be sure)
        if (nozzle.getPart() == null) {
            throw new UnsupportedOperationException("No part loaded that could be taken back.");
        }
        if (!nozzle.getPart().equals(getPart())) {
            throw new UnsupportedOperationException("Feeder: " + getName() + " - Can not take back " + nozzle.getPart().getName() + " this feeder only supports " + getPart().getName());
        }
        if (!canTakeBackPart()) {
            throw new UnsupportedOperationException("Feeder: " + getName() + " - Currently no known free space. Can not take back the part.");
        }

        // ok, now put the part back on the location of the last pick
        Location putLocation = getPickLocation();
        MovableUtils.moveToLocationAtSafeZ(nozzle, putLocation);
        nozzle.place();
        nozzle.moveToSafeZ();
        if (!nozzle.isPartOff()) {
            throw new Exception("Feeder: " + getName() + " - Putting part back failed, check nozzle tip");
        }
        // set pickLocation to null, avoid putting a second part on the same location
        pickLocation = null;
    }
    
    public CvPipeline getPipeline() {
        return pipeline;
    }

    public void resetPipeline() {
        pipeline = createDefaultPipeline();
    }

    @Override
    public Wizard getConfigurationWizard() {
        return new ReferenceLoosePartFeederConfigurationWizard(this);
    }

    @Override
    public String getPropertySheetHolderTitle() {
        return getClass().getSimpleName() + " " + getName();
    }

    @Override
    public PropertySheetHolder[] getChildPropertySheetHolders() {
        return null;
    }

    @Override
    public PropertySheet[] getPropertySheets() {
        return new PropertySheet[] {new PropertySheetWizardAdapter(getConfigurationWizard())};
    }

    @Override
    public Action[] getPropertySheetHolderActions() {
        return null;
    }

    public static CvPipeline createDefaultPipeline() {
        try {
            String xml = IOUtils.toString(ReferenceLoosePartFeeder.class.getResource(
                    "ReferenceLoosePartFeeder-DefaultPipeline.xml"));
            return new CvPipeline(xml);
        }
        catch (Exception e) {
            throw new Error(e);
        }
    }
}
