package com.cyanblob.claimderelict;

import fi.bugbyte.framework.Game;
import fi.bugbyte.framework.files.CompiledClassLoader;
import fi.bugbyte.framework.screen.ScalableIconTextButton;
import fi.bugbyte.framework.screen.StageButton;
import fi.bugbyte.framework.screen.StageButton.clickHandler;
import fi.bugbyte.gen.compiled.TextButtons2;
import fi.bugbyte.gen.compiled.TextIconButton1;
import fi.bugbyte.spacehaven.gui.GUI.SelectedElements;
import fi.bugbyte.spacehaven.gui.GameLog;
import fi.bugbyte.spacehaven.stuff.FactionUtils.FactionSide;
import fi.bugbyte.spacehaven.world.Ship;
import fi.bugbyte.spacehaven.world.World;
import fi.bugbyte.spacehaven.world.Ship.ShipSettings;
import fi.bugbyte.spacehaven.world.ShipHelper.ShipState;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;

@Aspect
public class claimDerelictAspect {

    private static ScalableIconTextButton purchaseButton;
    private World world = null;

    @Pointcut("call(* fi.bugbyte.spacehaven.gui.GUI.SelectedElements.addExploredDerelictShipStuff(..)) && within(fi.bugbyte..*)")
    public void addShipStuff() {
    }

    @After("addShipStuff()")
    public void updateGui(JoinPoint joinPoint) throws Throwable {
        SelectedElements _this = (SelectedElements) joinPoint.getThis();
        Object[] args = joinPoint.getArgs(); 

        if (args == null || args.length < 2) return; 
        Ship ship = (Ship) args[0];
        Object target = args[1]; 

        if (world == null) {
            world = ship.getWorld();
        }

        Method createClaimButton = _this.getClass().getDeclaredMethod("createClaimButton");
        createClaimButton.setAccessible(true);

        Method targetAddSelectionButton = target.getClass().getDeclaredMethod("addSelectionButton", StageButton.class);
        targetAddSelectionButton.setAccessible(true);

        if (ship.isDerelict() && !ship.isUnexplored() && !ship.isPlayerShip()) {
            try {
                createClaimButton.invoke(_this);

                int price = 1000;
                purchaseButton = (ScalableIconTextButton) getPurchaseButton(price);
                purchaseButton.setClickHandler(claimDerelictClickHandler(ship, price, world, _this));

                targetAddSelectionButton.invoke(target, (StageButton) purchaseButton);

            } catch (Exception e) {
                System.out.println("ClaimDerelict Mod Fehler in updateGui: " + e.getMessage());
            }
        }
    }

    TextIconButton1 getPurchaseButton(int price) {
        boolean bool = CompiledClassLoader.canCallOnGet;
        CompiledClassLoader.canCallOnGet = false;
        TextIconButton1 purchaseButton = TextButtons2.getIconBase2();
        CompiledClassLoader.canCallOnGet = bool;

        purchaseButton.setText("Purchase derelict: " + price + " credits");
        purchaseButton.toolTipText = "Allows purchasing a derelict ship";
        purchaseButton.icon = Game.library.getAnimation("claimShipButtonIcon", false);
        if (CompiledClassLoader.canCallOnGet)
            purchaseButton.onGet();

        return purchaseButton;
    }

    clickHandler claimDerelictClickHandler(Ship ship, int price, World world, SelectedElements _this) {
        return new clickHandler() {
            public void clicked() {
                try {
                    Field playerBankField = world.getClass().getDeclaredField("playerBank");
                    playerBankField.setAccessible(true);
                    Object playerBank = playerBankField.get(world);

                    if (playerBank == null) return;

                    Method getCreditsMethod = playerBank.getClass().getMethod("getCreditsAvailable");
                    int availableCredits = (Integer) getCreditsMethod.invoke(playerBank);

                    if (availableCredits < price) {
                        GameLog.addLog("Can not afford to purchase derelict", GameLog.LogType.Failure, ship);
                        return; 
                    }

                    // 1. Credits abziehen
                    Method addCreditsMethod = playerBank.getClass().getMethod("addCredits", int.class);
                    addCreditsMethod.invoke(playerBank, -price);

                    // 2. Das Schiff für das Vanilla-Claiming preparieren
                    Field shipSettingsField = ship.getClass().getDeclaredField("shipSettings");
                    shipSettingsField.setAccessible(true);
                    ShipSettings shipsettings = (ShipSettings) shipSettingsField.get(ship);
                    shipsettings.state = ShipState.Normal;

                    Field claimableField = ship.getClass().getDeclaredField("claimable");
                    claimableField.setAccessible(true);
                    claimableField.setBoolean(ship, true);

                    // 3. Vanilla Claim auslösen
                    Method claimMethod = null;
                    for (Method m : ship.getClass().getMethods()) {
                        if (m.getName().equals("claim") && m.getParameterCount() == 2) {
                            claimMethod = m;
                            break;
                        }
                    }

                    boolean success = false;
                    if (claimMethod != null) {
                        success = (Boolean) claimMethod.invoke(ship, FactionSide.Player, null);
                    }

                    if (success) {
                        GameLog.addLog("Derelict purchased! Rebooting UI...", GameLog.LogType.Good, ship);
                        
                        // 4. DEINE IDEE: DER VIRTUELLE SAVEGAME RELOAD
                        try {
                            Field guiInstanceField = Class.forName("fi.bugbyte.spacehaven.gui.GUI").getField("instance");
                            Object guiInstance = guiInstanceField.get(null);
                            
                            // Ruft exakt die Methode auf, die das Spiel beim Neuladen nutzt!
                            Method gameLoadedMethod = guiInstance.getClass().getMethod("gameLoaded");
                            gameLoadedMethod.invoke(guiInstance);
                            
                        } catch (Exception guiEx) {
                            GameLog.addLog("System reboot failed: " + guiEx.getMessage(), GameLog.LogType.Failure, ship);
                        }

                    } else {
                        // Sicherheitsnetz
                        addCreditsMethod.invoke(playerBank, price);
                        claimableField.setBoolean(ship, false); 
                        shipsettings.state = ShipState.Derelict;
                        GameLog.addLog("Error: Could not claim ship internally.", GameLog.LogType.Failure, ship);
                    }

                } catch (Exception e) {
                    System.out.println("ClaimDerelict Mod Fehler im ClickHandler: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        };
    }
}