/*
    This file is part of the HeavenMS MapleStory Server, commands OdinMS-based
    Copyleft (L) 2016 - 2019 RonanLana

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

/*
   @Author: Arthur L - Refactored command content into modules
*/
package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.command.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProviderFactory;
import provider.wz.WZFiles;

public class MaxSkillCommand extends Command {
    private static final Logger log = LoggerFactory.getLogger(MaxSkillCommand.class);
    {
        setDescription("Max out all job skills.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        for (Data skill_ : DataProviderFactory.getDataProvider(WZFiles.STRING).getData("Skill.img").getChildren()) {
            try {
                Skill skill = SkillFactory.getSkill(Integer.parseInt(skill_.getName()));
                player.changeSkillLevel(skill, (byte) skill.getMaxLevel(), skill.getMaxLevel(), -1);
            } catch (NumberFormatException nfe) {
                log.error("MaxSkillCommand error", nfe);
                break;
            } catch (NullPointerException npe) {
                log.error("MaxSkillCommand error nullptr", npe);
            }
        }

        if (player.getJob().isA(Job.ARAN1) || player.getJob().isA(Job.LEGEND)) {
            Skill skill = SkillFactory.getSkill(5001005);
            player.changeSkillLevel(skill, (byte) -1, -1, -1);
        } else {
            Skill skill = SkillFactory.getSkill(21001001);
            player.changeSkillLevel(skill, (byte) -1, -1, -1);
        }

        player.yellowMessage("Skills maxed out.");
    }
}
