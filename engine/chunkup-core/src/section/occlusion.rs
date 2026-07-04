pub fn fully_visible() -> [u64; 4] {
    [visibility_all(true); 4]
}

pub fn pack_occlusion(block_states: &[u8]) -> [u64; 4] {
    let mut opaque = [false; 16 * 16 * 16];
    for (index, &state) in block_states.iter().enumerate() {
        opaque[index] = state != 0 && state != 2;
    }

    let filled = opaque.iter().filter(|&&v| v).count();
    if filled == opaque.len() {
        return [visibility_all(false); 4];
    }
    if filled < 256 {
        return fully_visible();
    }

    [
        pack_direction_set(&opaque, 0b010101),
        pack_direction_set(&opaque, 0b010110),
        pack_direction_set(&opaque, 0b011001),
        pack_direction_set(&opaque, 0b100101),
    ]
}

fn visibility_all(visible: bool) -> u64 {
    if visible {
        (1u64 << 36) - 1
    } else {
        0
    }
}

fn pack_direction_set(opaque: &[bool], direction_set: i32) -> u64 {
    let mut visibility = 0u64;
    let origin_directions = (!direction_set) & 0b111111;

    for origin_dir in 0..6 {
        if (origin_directions & (1 << origin_dir)) == 0 {
            continue;
        }
        for target_dir in 0..6 {
            if (direction_set & (1 << target_dir)) != 0
                && can_reach_face(opaque, origin_dir, target_dir)
            {
                visibility |= 1u64 << bit(origin_dir, target_dir);
            }
        }
        visibility |= 1u64 << bit(origin_dir, origin_dir);
    }
    visibility
}

fn can_reach_face(opaque: &[bool], from: i32, to: i32) -> bool {
    let (mut min_x, mut min_y, mut min_z) = (0, 0, 0);
    let (mut max_x, mut max_y, mut max_z) = (15, 15, 15);

    match from {
        0 => max_y = 0,
        1 => min_y = 15,
        2 => max_z = 0,
        3 => min_z = 15,
        4 => max_x = 0,
        5 => min_x = 15,
        _ => return false,
    }

    for x in min_x..=max_x {
        for y in min_y..=max_y {
            for z in min_z..=max_z {
                if !opaque[index(x, y, z)] && ray_reaches_face(opaque, x, y, z, to) {
                    return true;
                }
            }
        }
    }
    false
}

fn ray_reaches_face(opaque: &[bool], x: i32, y: i32, z: i32, to: i32) -> bool {
    let (mut cx, mut cy, mut cz) = (x, y, z);
    loop {
        match to {
            0 => {
                if cy == 0 {
                    return true;
                }
                cy -= 1;
            }
            1 => {
                if cy == 15 {
                    return true;
                }
                cy += 1;
            }
            2 => {
                if cz == 0 {
                    return true;
                }
                cz -= 1;
            }
            3 => {
                if cz == 15 {
                    return true;
                }
                cz += 1;
            }
            4 => {
                if cx == 0 {
                    return true;
                }
                cx -= 1;
            }
            5 => {
                if cx == 15 {
                    return true;
                }
                cx += 1;
            }
            _ => return false,
        }
        if opaque[index(cx, cy, cz)] {
            return false;
        }
    }
}

fn index(x: i32, y: i32, z: i32) -> usize {
    (x | (z << 4) | (y << 8)) as usize
}

fn bit(from: i32, to: i32) -> u32 {
    ((from * 8) + to) as u32
}
